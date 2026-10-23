package ru.homescreen.tv.data

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * One history for the household's TVs: «Продолжить», episode marks, picked season and voice, bookmarks.
 *
 * TVs are linked by hand: «Связать с другим ТВ» in the gear menu on both within two minutes. While waiting, each TV
 * announces itself by UDP broadcast; the two agree on a key over ECDH (P-256) and from then on share a group key that
 * encrypts everything they exchange (AES-GCM). Linked TVs announce themselves once a minute, so a new address is found
 * on its own. An exchange is one TCP request: «here is all I have», answered with all the other has after merging it;
 * both keep the newer of every record ([History.merge], [Bookmarks.merge]), so the order and repeats do not matter.
 */
class Sync(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Everything shared, as one JSON object. Read off the main thread. */
    private val state: () -> JSONObject,
    /** Takes a linked TV's state; true if anything changed. Called off the main thread. */
    private val merge: (JSONObject) -> Boolean,
) {
    data class Peer(val id: String, val name: String, val host: String, val port: Int)

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val id: String = prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    @Volatile private var key: ByteArray? = prefs.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    private val group: String get() = key?.let { hex(sha256(it)).take(16) } ?: ""
    private val name: String
        get() = Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    /** Linked TVs, as last heard from. */
    var peers by mutableStateOf(loadPeers())
        private set
    /** Waiting for the other TV to be linked (two minutes). */
    var pairing by mutableStateOf(false)
        private set
    /** A line for the user ("Связано с …"); the screen shows it once and clears it. */
    var note by mutableStateOf<String?>(null)

    @Volatile private var pairUntil = 0L
    private val pairBusy = AtomicBoolean(false)
    private val lastSync = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val syncLock = Mutex()
    private var server: ServerSocket? = null
    private var listener: DatagramSocket? = null
    private var announceJob: Job? = null
    private var changeJob: Job? = null
    private var lastAll = 0L
    private var lock: WifiManager.MulticastLock? = null

    fun start() {
        // Wi-Fi drivers may drop broadcasts to save power unless an app asks for them.
        lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("showylauncher-sync")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        serve()
        listen()
        announceLoop()
        scope.launch {
            while (true) {
                delay(15 * 60_000L)
                syncAll()
            }
        }
        scope.launch { syncAll() }
    }

    fun stop() {
        runCatching { server?.close() }
        runCatching { listener?.close() }
        runCatching { lock?.release() }
    }

    /** Back to Home: a TV that was off may have missed a change. */
    fun resume() {
        if (System.currentTimeMillis() - lastAll > 60_000L) scope.launch { syncAll() }
    }

    /** Something changed here: the linked TVs hear of it a moment later (a burst of changes goes as one). */
    fun changed() {
        if (key == null) return
        changeJob?.cancel()
        changeJob = scope.launch {
            delay(2000)
            syncAll()
        }
    }

    /** «Связать с другим ТВ»: two minutes of looking for another TV doing the same. Pressed again, it stops. */
    fun pair() {
        if (pairing) { endPairing(null); return }
        pairUntil = System.currentTimeMillis() + PAIR_MS
        pairing = true
        announceLoop()
        scope.launch {
            delay(PAIR_MS)
            if (pairing && System.currentTimeMillis() >= pairUntil) endPairing("Другой ТВ не нашёлся")
        }
    }

    /** Leaves the group: this TV keeps its history but no longer shares it. */
    fun unlink() {
        key = null
        prefs.edit().remove("key").apply()
        savePeers(emptyList())
    }

    private fun endPairing(message: String?) {
        pairUntil = 0L
        pairing = false
        message?.let { note = it }
        announceLoop()
    }

    private fun pairingNow() = System.currentTimeMillis() < pairUntil

    // ---------- Exchange ----------

    private suspend fun syncAll() {
        if (key == null) return
        lastAll = System.currentTimeMillis()
        peers.forEach { syncWith(it) }
    }

    private suspend fun syncWith(p: Peer): Boolean = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val k = key ?: return@withContext false
            val body = JSONObject().put("n", name).put("s", state())
            val iv = random(12)
            val req = JSONObject().put("t", "sync").put("id", id).put("g", group).put("p", TCP_PORT).put("iv", b64(iv))
                .put("d", b64(aes(Cipher.ENCRYPT_MODE, k, iv, body.toString().toByteArray(), "sync")))
            val resp = request(p.host, p.port, req)
            if (resp == null || resp.has("e")) {
                Log.i(TAG, "sync: ${p.host} -> ${resp?.optString("e") ?: "no answer"}")
                return@withContext false
            }
            try {
                val data = JSONObject(String(aes(Cipher.DECRYPT_MODE, k, b64d(resp.getString("iv")), b64d(resp.getString("d")), "sync-r")))
                val changed = merge(data.getJSONObject("s"))
                lastSync[p.id] = System.currentTimeMillis()
                data.optString("n").takeIf { it.isNotEmpty() && it != p.name }?.let { upsert(p.copy(name = it)) }
                Log.i(TAG, "sync: ${p.host} ok, changed=$changed")
                true
            } catch (e: Exception) {
                Log.i(TAG, "sync: ${p.host} bad answer: $e")
                false
            }
        }
    }

    /** One request per connection: a line of JSON there, a line back. */
    private fun request(host: String, port: Int, body: JSONObject): JSONObject? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 15_000
            s.getOutputStream().apply { write((body.toString() + "\n").toByteArray()); flush() }
            readLine(s.getInputStream().buffered())?.let { JSONObject(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun serve() {
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(TCP_PORT)) }
        } catch (e: Exception) {
            Log.w(TAG, "sync: no server: $e")
            return
        }
        server = ss
        thread(name = "sync-server", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                thread(name = "sync-conn", isDaemon = true) {
                    s.use {
                        try {
                            it.soTimeout = 15_000
                            val req = JSONObject(readLine(it.getInputStream().buffered()) ?: return@use)
                            val host = it.inetAddress.hostAddress ?: return@use
                            val resp = when (req.optString("t")) {
                                "pair" -> answerPair(req, host)
                                "sync" -> answerSync(req, host)
                                else -> error("?")
                            }
                            it.getOutputStream().apply { write((resp.toString() + "\n").toByteArray()); flush() }
                        } catch (e: Exception) {
                            Log.i(TAG, "sync: request failed: $e")
                        }
                    }
                }
            }
        }
    }

    private fun answerSync(req: JSONObject, host: String): JSONObject {
        val k = key ?: return error("group")
        if (req.optString("g") != group) return error("group")
        val data = try {
            JSONObject(String(aes(Cipher.DECRYPT_MODE, k, b64d(req.getString("iv")), b64d(req.getString("d")), "sync")))
        } catch (e: Exception) {
            return error("auth")
        }
        val changed = merge(data.optJSONObject("s") ?: JSONObject())
        val peer = Peer(req.optString("id"), data.optString("n"), host, req.optInt("p", TCP_PORT))
        lastSync[peer.id] = System.currentTimeMillis()
        scope.launch { upsert(peer) }
        Log.i(TAG, "sync: from $host, changed=$changed")
        val iv = random(12)
        val out = JSONObject().put("n", name).put("s", state())
        return JSONObject().put("iv", b64(iv)).put("d", b64(aes(Cipher.ENCRYPT_MODE, k, iv, out.toString().toByteArray(), "sync-r")))
    }

    // ---------- Linking ----------

    /**
     * The TV that hands out the group key: the one already in a group if only one is, else the smaller id. The other
     * one asks ([pairWith]), so the two never both ask.
     */
    private fun leads(otherGroup: String, otherId: String): Boolean {
        val mine = group
        return when {
            mine.isNotEmpty() && otherGroup.isEmpty() -> true
            mine.isEmpty() && otherGroup.isNotEmpty() -> false
            else -> id < otherId
        }
    }

    private fun answerPair(req: JSONObject, host: String): JSONObject {
        if (!pairingNow()) return error("not pairing")
        if (!leads(req.optString("g"), req.optString("id"))) return error("not leader")
        val k = key ?: random(32).also { setKey(it) }
        val kp = ecKeys()
        val secret = agree(kp.private, req.getString("pub"))
        val iv = random(12)
        val peer = Peer(req.optString("id"), req.optString("n"), host, req.optInt("p", TCP_PORT))
        scope.launch {
            upsert(peer)
            endPairing("Связано с «${peer.name}»")
        }
        Log.i(TAG, "sync: linked $host (gave the key)")
        return JSONObject().put("id", id).put("n", name).put("pub", b64(kp.public.encoded)).put("iv", b64(iv))
            .put("k", b64(aes(Cipher.ENCRYPT_MODE, secret, iv, k, "pair")))
    }

    private fun pairWith(a: Announce, host: String) {
        if (!pairBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val got = withContext(Dispatchers.IO) {
                    val kp = ecKeys()
                    val resp = request(host, a.port, JSONObject().put("t", "pair").put("id", id).put("n", name).put("g", group)
                        .put("p", TCP_PORT).put("pub", b64(kp.public.encoded))) ?: return@withContext null
                    if (resp.has("e")) return@withContext null
                    val secret = agree(kp.private, resp.getString("pub"))
                    aes(Cipher.DECRYPT_MODE, secret, b64d(resp.getString("iv")), b64d(resp.getString("k")), "pair").takeIf { it.size == 32 }
                } ?: return@launch
                // A new group: the TVs of the old one are left behind.
                val newGroup = !got.contentEquals(key)
                setKey(got)
                val peer = Peer(a.id, a.name, host, a.port)
                if (newGroup) savePeers(listOf(peer)) else upsert(peer)
                Log.i(TAG, "sync: linked $host (took the key)")
                endPairing("Связано с «${a.name}»")
                syncWith(peer)
            } catch (e: Exception) {
                Log.i(TAG, "sync: linking failed: $e")
            } finally {
                pairBusy.set(false)
            }
        }
    }

    private fun setKey(k: ByteArray) {
        key = k
        prefs.edit().putString("key", b64(k)).apply()
    }

    // ---------- Finding each other ----------

    private data class Announce(val id: String, val name: String, val port: Int, val group: String, val pair: Boolean)

    /** Linked TVs say where they are once a minute; a TV waiting to be linked every two seconds. Others stay quiet. */
    private fun announceLoop() {
        announceJob?.cancel()
        announceJob = scope.launch {
            while (true) {
                val pairingNow = pairingNow()
                if (pairingNow || key != null) withContext(Dispatchers.IO) { announce(pairingNow) }
                delay(if (pairingNow) 2000L else 60_000L)
            }
        }
    }

    private fun announce(pairingNow: Boolean) {
        val msg = JSONObject().put("a", APP).put("v", 1).put("id", id).put("n", name).put("p", TCP_PORT).put("g", group)
            .put("pair", pairingNow).toString().toByteArray()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                for (to in broadcasts()) runCatching { s.send(DatagramPacket(msg, msg.size, to, UDP_PORT)) }
            }
        } catch (e: Exception) {
            Log.i(TAG, "sync: announce failed: $e")
        }
    }

    private fun broadcasts(): Set<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        }
        out += InetAddress.getByName("255.255.255.255")
        return out
    }

    private fun listen() {
        val socket = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(UDP_PORT)) }
        } catch (e: Exception) {
            Log.w(TAG, "sync: not listening: $e")
            return
        }
        listener = socket
        thread(name = "sync-listen", isDaemon = true) {
            val buf = ByteArray(2048)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (e: Exception) { if (socket.isClosed) break else continue }
                val host = p.address?.hostAddress ?: continue
                val a = try {
                    val o = JSONObject(String(p.data, 0, p.length))
                    if (o.optString("a") != APP || o.optInt("v") < 1) continue
                    Announce(o.getString("id"), o.optString("n"), o.optInt("p", TCP_PORT), o.optString("g"), o.optBoolean("pair"))
                } catch (e: Exception) {
                    continue
                }
                if (a.id != id) scope.launch { heard(a, host) }
            }
        }
    }

    private fun heard(a: Announce, host: String) {
        val mine = group
        if (pairingNow() && a.pair) {
            when {
                mine.isNotEmpty() && a.group == mine -> {
                    upsert(Peer(a.id, a.name, host, a.port))
                    endPairing("Уже связано с «${a.name}»")
                }
                !leads(a.group, a.id) -> pairWith(a, host)
            }
            return
        }
        if (mine.isEmpty() || a.group != mine) return
        val known = peers.firstOrNull { it.id == a.id }
        val peer = Peer(a.id, a.name, host, a.port)
        if (known != peer) upsert(peer)
        // A TV just switched on, or one that moved: catch up at once.
        if (known?.host != host || System.currentTimeMillis() - (lastSync[a.id] ?: 0L) > 10 * 60_000L) scope.launch { syncWith(peer) }
    }

    // ---------- Peers ----------

    private fun upsert(p: Peer) {
        if (p.id.isEmpty()) return
        savePeers(peers.filter { it.id != p.id } + p)
    }

    private fun savePeers(list: List<Peer>) {
        peers = list
        prefs.edit().putString("peers", JSONArray(list.map {
            JSONObject().put("id", it.id).put("n", it.name).put("h", it.host).put("p", it.port)
        }).toString()).apply()
    }

    private fun loadPeers(): List<Peer> = try {
        val a = JSONArray(prefs.getString("peers", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Peer(o.getString("id"), o.optString("n"), o.getString("h"), o.optInt("p", TCP_PORT)) } }
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        const val TAG = "homescreen"
        const val APP = "showylauncher"
        const val UDP_PORT = 47123
        const val TCP_PORT = 47124
        const val PAIR_MS = 120_000L
        /** A request is a few hundred kilobytes at most; anything longer is not ours. */
        const val MAX_LINE = 8 * 1024 * 1024

        val rnd = SecureRandom()

        fun error(e: String): JSONObject = JSONObject().put("e", e)

        fun readLine(input: InputStream): String? {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() > 0) out.toString("UTF-8") else null
                if (b == '\n'.code) return out.toString("UTF-8")
                out.write(b)
                if (out.size() > MAX_LINE) return null
            }
        }

        fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray, aad: String): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(aad.toByteArray())
                doFinal(data)
            }

        fun ecKeys(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        /** The shared secret of the two key pairs, hashed into an AES key for handing over the group key. */
        fun agree(mine: PrivateKey, theirs: String): ByteArray {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64d(theirs)))
            val secret = KeyAgreement.getInstance("ECDH").run {
                init(mine)
                doPhase(pub, true)
                generateSecret()
            }
            return sha256(secret + "showylauncher-pair".toByteArray())
        }
    }
}
