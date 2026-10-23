package ru.homescreen.tv.data

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lampac's "remote client hub": for some sources the server cannot fetch the page itself and asks the client to, over
 * a WebSocket (`{"method": ..., "args": [...]}` JSON, as in showypro.com/js/nws-client-es5.js). The launcher runs the
 * plain HTTP requests and posts the bodies back. Requests to run JavaScript (`eval`) get an empty answer.
 */
object Rch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var registered = false
    private var ready: CompletableDeferred<Boolean>? = null

    /** Connects and registers when needed; true once the server has accepted this client. */
    suspend fun ensure(answer: JSONObject): Boolean {
        val nws = answer.optString("nws").ifEmpty { answer.optString("ws") }
        if (nws.isEmpty()) return false
        if (socket != null && registered) return true
        val wait = synchronized(this) {
            ready?.takeIf { !it.isCompleted } ?: CompletableDeferred<Boolean>().also { connect(nws, it) }
        }
        return withTimeoutOrNull(10_000) { wait.await() } ?: false
    }

    /** Another server was picked: the next request that needs the hub connects to that one. */
    fun reset() {
        synchronized(this) {
            socket?.cancel()
            socket = null
            registered = false
            ready = null
        }
    }

    private fun connect(nws: String, done: CompletableDeferred<Boolean>) {
        ready = done
        registered = false
        socket?.cancel()
        val url = Uri.parse(nws).buildUpon().appendQueryParameter("id", Showy.nwsId).appendQueryParameter("ver", "1").build()
        socket = Net.client.newWebSocket(Request.Builder().url(url.toString()).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                val args = m.optJSONArray("args") ?: JSONArray()
                when (m.optString("method")) {
                    "Connected" -> invoke(webSocket, "RchRegistry", JSONObject()
                        .put("host", "").put("rchtype", "apk").put("apkVersion", 0).put("player", "android"))
                    "RchRegistry" -> { registered = true; done.complete(true) }
                    "RchClient" -> scope.launch { serve(webSocket, args) }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.w("homescreen", "rch: $t")
                if (socket === webSocket) { socket = null; registered = false }
                done.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) { socket = null; registered = false }
                done.complete(false)
            }
        })
    }

    private fun invoke(ws: WebSocket, method: String, vararg args: Any) {
        ws.send(JSONObject().put("method", method).put("args", JSONArray(args.toList())).toString())
    }

    /** `RchClient(rchId, url, data, headers, returnHeaders)`: fetch [url] and post the body to /rch/result. */
    private fun serve(ws: WebSocket, args: JSONArray) {
        val id = args.optString(0)
        val url = args.optString(1)
        val data = args.opt(2)?.takeIf { it != JSONObject.NULL && it != false && it.toString().isNotEmpty() }?.toString()
        val headers = args.optJSONObject(3)
        val returnHeaders = args.optBoolean(4)
        val body: String = when (url) {
            "ping" -> "pong"
            "eval", "evalrun" -> ""
            else -> try {
                val b = Request.Builder().url(url)
                headers?.keys()?.forEach { k -> b.header(k, headers.optString(k)) }
                if (data != null) b.post(data.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                Net.client.newCall(b.build()).execute().use { r ->
                    val text = r.body?.string() ?: ""
                    if (!returnHeaders) text else {
                        val h = JSONObject()
                        r.headers.names().forEach { n -> h.put(n, r.header(n)) }
                        JSONObject().put("body", text).put("headers", h).toString()
                    }
                }
            } catch (e: Exception) {
                ""
            }
        }
        if (url == "evalrun") return
        try {
            Net.client.newCall(
                Request.Builder().url("${Showy.host}/rch/result?id=" + Uri.encode(id))
                    .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType())).build(),
            ).execute().close()
        } catch (e: Exception) {
            invoke(ws, "RchResult", id, "")
        }
    }
}
