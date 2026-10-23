package ru.homescreen.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Net {
    lateinit var client: OkHttpClient
        private set

    fun init(context: Context) {
        if (::client.isInitialized) return
        client = OkHttpClient.Builder()
            .dns(SafeDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
            .build()
    }

    /** GET and parse a JSON object; null on any network or parse error. Some APIs want the client named ([userAgent]). */
    suspend fun json(url: String, userAgent: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(url)
            userAgent?.let { b.header("User-Agent", it) }
            client.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** True when the URL answers 2xx within the client's timeouts. */
    suspend fun reachable(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * The home DNS answers 127.0.0.1 for api.themoviedb.org, and the CUB mirror redirects some requests (a season's
 * episodes) there. Hosts the system resolves to loopback are looked up over HTTPS (Cloudflare, then Google) instead.
 */
private object SafeDns : Dns {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val doh by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val system = try { Dns.SYSTEM.lookup(hostname) } catch (e: UnknownHostException) { emptyList() }
        if (system.isNotEmpty() && system.none { it.isLoopbackAddress || it.isAnyLocalAddress }) return system
        cache[hostname]?.let { (until, list) -> if (System.currentTimeMillis() < until) return list }
        val found = overHttps(hostname)
        if (found.isNotEmpty()) {
            cache[hostname] = System.currentTimeMillis() + 30 * 60_000L to found
            return found
        }
        if (system.isNotEmpty()) return system
        throw UnknownHostException(hostname)
    }

    private fun overHttps(host: String): List<InetAddress> {
        for (base in listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")) {
            try {
                val req = Request.Builder().url("$base?name=$host&type=A").header("Accept", "application/dns-json").build()
                val body = doh.newCall(req).execute().use { it.body?.string() } ?: continue
                val answers = JSONObject(body).optJSONArray("Answer") ?: continue
                val out = (0 until answers.length()).mapNotNull { i ->
                    answers.optJSONObject(i)?.takeIf { it.optInt("type") == 1 }?.optString("data")
                }.map { InetAddress.getByName(it) }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
            }
        }
        return emptyList()
    }
}
