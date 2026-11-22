package ru.homescreen.tv.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    suspend fun json(url: String, userAgent: String? = null): JSONObject? = try {
        val b = Request.Builder().url(url)
        userAgent?.let { b.header("User-Agent", it) }
        client.newCall(b.build()).await { r -> if (!r.isSuccessful) null else r.body?.string()?.let { JSONObject(it) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** True when the URL answers 2xx within the client's timeouts. */
    suspend fun reachable(url: String): Boolean = try {
        client.newCall(Request.Builder().url(url).head().build()).await { it.isSuccessful }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

/**
 * Runs the call on OkHttp's threads and hands [read] the response there, closing it after. A cancelled coroutine
 * cancels the call: a screen left mid-request does not hold a connection for the whole timeout.
 */
suspend fun <T> Call.await(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use(read)
            } catch (e: Exception) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(result)
        }
    })
}

/**
 * Addresses that answer, in two cases:
 * - The home DNS answers 127.0.0.1 for api.themoviedb.org, and the CUB mirror redirects some requests (a season's
 *   episodes) there. Hosts the system resolves to loopback are looked up over HTTPS (Cloudflare, then Google) instead.
 * - The TMDB mirrors are behind Cloudflare, and some providers stall connections to part of its addresses after the
 *   first ~16 KB: small answers pass, posters never arrive. Which addresses a TV gets depends on its DNS (one with
 *   AdGuard as private DNS got only stalled ones), so the mirrors' addresses from the system and over HTTPS are each
 *   tried with a real download, and the ones that finish go first.
 */
private object SafeDns : Dns {
    /** Cloudflare serves any of its sites from any of its addresses: one download tells about an address for both. */
    private val MIRRORS = setOf("imagetmdb.com", "apitmdb.cub.best")
    private const val PROBE = "https://imagetmdb.com/t/p/w1280/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"
    /** The probe is 43 KB; a stalled connection never gets this far. */
    private const val PROBE_MIN = 24 * 1024

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    /** Whether an address finished the probe, until when that holds. */
    private val verdicts = ConcurrentHashMap<InetAddress, Pair<Long, Boolean>>()
    /** Probes still running: a stalled one ends only at its timeout, and is not started again meanwhile. */
    private val probing = ConcurrentHashMap.newKeySet<InetAddress>()
    private val probeLock = Any()
    private val doh by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    }
    private val prober by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val system = try { Dns.SYSTEM.lookup(hostname) } catch (e: UnknownHostException) { emptyList() }
        if (hostname in MIRRORS) return mirror(hostname, system.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress })
        if (system.isNotEmpty() && system.none { it.isLoopbackAddress || it.isAnyLocalAddress }) return system
        val found = cachedOverHttps(hostname)
        if (found.isNotEmpty()) return found
        if (system.isNotEmpty()) return system
        throw UnknownHostException(hostname)
    }

    private fun cachedOverHttps(host: String): List<InetAddress> {
        cache[host]?.let { (until, list) -> if (System.currentTimeMillis() < until) return list }
        return overHttps(host).also { if (it.isNotEmpty()) cache[host] = System.currentTimeMillis() + 30 * 60_000L to it }
    }

    private fun mirror(host: String, system: List<InetAddress>): List<InetAddress> {
        val all = (system + cachedOverHttps(host)).distinct()
        if (all.isEmpty()) throw UnknownHostException(host)
        val untried = { all.filter { known(it) == null && it !in probing } }
        if (untried().isNotEmpty()) synchronized(probeLock) { probe(untried()) }
        return byVerdict(all, ::known)
    }

    private fun known(ip: InetAddress): Boolean? =
        verdicts[ip]?.takeIf { System.currentTimeMillis() < it.first }?.second

    /**
     * Downloads [PROBE] through each address at once. Back as soon as one finishes (a stalled one only ends at the
     * timeout) or all have answered; the rest still record their verdict for the next lookup.
     */
    private fun probe(ips: List<InetAddress>) {
        if (ips.isEmpty()) return
        val answers = LinkedBlockingQueue<Boolean>()
        for (ip in ips) {
            probing += ip
            fun done(ok: Boolean) {
                verdicts[ip] = System.currentTimeMillis() + (if (ok) 30 else 10) * 60_000L to ok
                probing -= ip
                android.util.Log.i("homescreen", "dns: ${ip.hostAddress} ${if (ok) "downloads" else "stalls"}")
                answers.add(ok)
            }
            val only = object : Dns { override fun lookup(hostname: String) = listOf(ip) }
            prober.newBuilder().dns(only).build().newCall(Request.Builder().url(PROBE).build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = done(false)

                    override fun onResponse(call: Call, response: Response) = done(
                        try { response.use { it.isSuccessful && (it.body?.bytes()?.size ?: 0) >= PROBE_MIN } } catch (e: IOException) { false }
                    )
                })
        }
        val until = System.currentTimeMillis() + 6000
        repeat(ips.size) {
            val ok = answers.poll(until - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return
            if (ok) return
        }
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

/**
 * Addresses that finished the probe first, then the ones not tried yet, then the stalled ones; in the order given
 * within each. When none passed, the order stays as it came.
 */
internal fun <T> byVerdict(all: List<T>, known: (T) -> Boolean?): List<T> =
    all.sortedBy { when (known(it)) { true -> 0; null -> 1; false -> 2 } }
