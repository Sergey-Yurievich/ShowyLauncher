package ru.homescreen.tv.data

import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.common.PlaybackException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A file played from the TV's disk while one connection downloads it ahead of the player, as far as [ahead] bytes past
 * where it reads: a torrent slower than the film still stutters, but less, and a pause lets it fill up. What is there
 * is kept in chunk files of [CHUNK] bytes; past [store] what was watched goes first. Seeking into what is
 * already down is instant; anywhere else the download starts over from there. Each file has its own folder, deleted
 * once [close] has stopped the download.
 */
@OptIn(UnstableApi::class)
class Prefetch private constructor(
    private val url: String,
    private val headers: Map<String, String>,
    private val dir: File,
    val length: Long,
    private var ahead: Long,
    private var store: Long,
) : Closeable {
    private val lock = Object()
    private val have = Ranges()
    /** Chunk files by index; each one is sought and read or written under its own monitor. */
    private val chunks = HashMap<Long, RandomAccessFile>()
    /** Where the player last read. */
    private var reader = 0L
    private var failure: IOException? = null
    private var closed = false
    /** The download under way, where it has got to and the chunk it writes. */
    private var call: Call? = null
    private var at = 0L
    private var writing = -1L
    /** [call] was cancelled because the player wants another place: not a failure. */
    private var cut = false
    /** Downloads failed in a row without a byte in between; the download thread's own. */
    private var fails = 0

    /** Where the bytes on disk that run unbroken from [pos] end: [pos] itself when it isn't down, [length] when it all is. */
    fun downFrom(pos: Long): Long = synchronized(lock) { have.firstMissing(pos) }

    private val received = AtomicLong()
    private var mark = 0L
    private var markAt = 0L
    private var bits = 0L

    /** Bits per second over the last couple of seconds; 0 while the window is full or the file is all here. */
    val speed: Long
        get() = synchronized(lock) {
            val now = SystemClock.uptimeMillis()
            if (now - markAt >= 2000) {
                val r = received.get()
                bits = if (markAt == 0L) 0 else (r - mark) * 8000 / (now - markAt)
                mark = r
                markAt = now
            }
            bits
        }

    init {
        Thread(::run, "prefetch").apply { isDaemon = true }.start()
    }

    /** Stops the download without waiting for it; its thread then deletes the files. */
    override fun close() {
        synchronized(lock) {
            closed = true
            call?.cancel()
            lock.notifyAll()
        }
    }

    /** Copies up to [len] bytes at [pos], waiting until the download has them. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val n: Int
        val f: RandomAccessFile
        synchronized(lock) {
            reader = pos
            lock.notifyAll()
            while (true) {
                if (closed) throw IOException("closed")
                val ready = have.readyAt(pos)
                if (ready > 0) { n = minOf(len.toLong(), ready, CHUNK - pos % CHUNK).toInt(); break }
                // A download elsewhere won't bring this: stop it now, not when its stalled read gives up.
                val c = call
                if (c != null && !(pos >= at && pos - at <= SLACK)) { cut = true; call = null; c.cancel() }
                failure?.let { throw it }
                try { lock.wait(500) } catch (e: InterruptedException) { throw InterruptedIOException() }
            }
            f = chunks[pos / CHUNK] ?: throw IOException("chunk gone")
        }
        // Not a FileChannel: the player interrupts its loader thread on a seek, and that would close the channel for good.
        synchronized(f) {
            f.seek(pos % CHUNK)
            f.readFully(buf, off, n)
        }
        return n
    }

    private fun run() {
        // Below the TV's own interface: the volume bar and menus come first.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            while (true) {
                val start = synchronized(lock) {
                    if (closed) return
                    val from = reader
                    val s = have.firstMissing(from)
                    if (s >= length || s - from > ahead) { lock.wait(1000); null } else s
                } ?: continue
                try {
                    download(start)
                } catch (e: Exception) {
                    val wasCut = synchronized(lock) { if (closed) return; cut.also { cut = false } }
                    if (wasCut) continue
                    fails++
                    Log.w("homescreen", "prefetch: at $start, try $fails: $e")
                    // The player gives up only after the stream has failed for a while: a torrent's peers come and go.
                    synchronized(lock) {
                        if (fails >= 6) { failure = e as? IOException ?: IOException(e); lock.notifyAll() }
                        if (!closed) lock.wait(minOf(fails * 1000L, 5000L))
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Nobody interrupts this thread; if something does, the player is told by [closed].
        } finally {
            synchronized(lock) {
                closed = true
                chunks.values.forEach { runCatching { it.close() } }
                chunks.clear()
                lock.notifyAll()
            }
            dir.deleteRecursively()
            synchronized(live) { live -= dir }
        }
    }

    /** Streams from [start] until the player wants something else, the window is full or the stream meets what is there. */
    private fun download(start: Long) {
        val b = Request.Builder().url(url).header("Range", "bytes=$start-")
        headers.forEach { (k, v) -> b.header(k, v) }
        val c = stream.newCall(b.build())
        synchronized(lock) {
            if (closed) return
            call = c; at = start; cut = false
        }
        try {
            c.execute().use { r ->
                if (r.code == 206) {
                    val (from, size) = contentRange(r.header("Content-Range")) ?: throw IOException("no Content-Range at $start")
                    if (from != start || size >= 0 && size != length) throw IOException("got $from of $size at $start")
                } else if (!(r.code == 200 && start == 0L)) throw IOException("HTTP ${r.code} at $start")
                val src = r.body?.byteStream() ?: throw IOException("no body")
                val buf = ByteArray(256 * 1024)
                var pos = start
                var paced = SystemClock.uptimeMillis()
                while (pos < length) {
                    val f = synchronized(lock) {
                        if (closed || call !== c) return
                        val need = have.firstMissing(reader)
                        // Just past this stream (a container skipping a little) it is coming anyway; elsewhere, start over.
                        if (need != pos && !(need > pos && need - pos <= SLACK)) return
                        if (pos - reader > ahead) return
                        at = pos
                        writing = pos / CHUNK
                        chunks.getOrPut(writing) { RandomAccessFile(File(dir, "$writing"), "rw") }
                    }
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), length - pos, CHUNK - pos % CHUNK).toInt())
                    if (n < 0) throw IOException("stream ended at $pos")
                    write(f, pos, buf, n)
                    // To the disk every few megabytes: a backlog of hundreds, written out at once, stalls the whole TV.
                    if ((pos + n) / SYNC != pos / SYNC || pos + n == length) runCatching { Os.fdatasync(f.fd) }
                    received.addAndGet(n.toLong())
                    fails = 0
                    val far = synchronized(lock) {
                        have.add(pos, pos + n)
                        failure = null
                        lock.notifyAll()
                        evict()
                        pos + n - reader > CALM
                    }
                    pos += n
                    // Well ahead of the player there is no hurry: a calmer download leaves the disk to the rest of the TV.
                    val now = SystemClock.uptimeMillis()
                    paced = if (far) maxOf(paced, now - 1000) + n * 1000L / CALM_RATE else now
                    synchronized(lock) {
                        while (!closed && call === c) {
                            val wait = paced - SystemClock.uptimeMillis()
                            if (wait <= 0) break
                            lock.wait(wait)
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (call === c) call = null
                writing = -1
            }
        }
    }

    private fun write(f: RandomAccessFile, pos: Long, buf: ByteArray, n: Int) {
        try {
            synchronized(f) {
                f.seek(pos % CHUNK)
                f.write(buf, 0, n)
            }
        } catch (e: IOException) {
            // The disk filled up under the download (other apps write too): keep less, the retry then has room.
            if (e.message?.let { "ENOSPC" in it || "No space" in it } == true) synchronized(lock) {
                store = maxOf(4 * CHUNK, have.total - 2 * CHUNK)
                ahead = minOf(ahead, store * 3 / 4)
                Log.w("homescreen", "prefetch: disk full, keeping ${store shr 20} MB")
                evict()
            }
            throw e
        }
    }

    /** Over [store], chunks go by [victim]. Holds [lock]. */
    private fun evict() {
        while (have.total > store) {
            val far = victim(chunks.keys, reader / CHUNK, writing) ?: return
            chunks.remove(far)?.let { runCatching { it.close() } }
            File(dir, "$far").delete()
            have.remove(far * CHUNK, (far + 1) * CHUNK)
        }
    }

    /** The player's side: a Media3 source reading [Prefetch] instead of the network. */
    class Source(private val p: Prefetch) : BaseDataSource(true) {
        private var uri: Uri? = null
        private var pos = 0L
        private var left = 0L

        override fun open(spec: DataSpec): Long {
            uri = spec.uri
            transferInitializing(spec)
            if (spec.position > p.length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            pos = spec.position
            left = if (spec.length != C.LENGTH_UNSET.toLong()) minOf(spec.length, p.length - pos) else p.length - pos
            transferStarted(spec)
            return left
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val n = p.read(pos, buffer, offset, minOf(length.toLong(), left).toInt())
            pos += n
            left -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            if (uri != null) transferEnded()
            uri = null
        }
    }

    /** Sorted, merged byte ranges [start, end). */
    class Ranges {
        private val list = ArrayList<LongArray>()
        var total = 0L
            private set

        fun add(start: Long, end: Long) {
            var s = start
            var e = end
            val it = list.iterator()
            while (it.hasNext()) {
                val r = it.next()
                if (r[1] < s || r[0] > e) continue
                s = minOf(s, r[0]); e = maxOf(e, r[1])
                total -= r[1] - r[0]
                it.remove()
            }
            val at = list.indexOfFirst { it[0] > s }.let { if (it < 0) list.size else it }
            list.add(at, longArrayOf(s, e))
            total += e - s
        }

        fun remove(start: Long, end: Long) {
            val keep = ArrayList<LongArray>()
            for (r in list) {
                if (r[1] <= start || r[0] >= end) { keep += r; continue }
                if (r[0] < start) keep += longArrayOf(r[0], start)
                if (r[1] > end) keep += longArrayOf(end, r[1])
            }
            list.clear(); list.addAll(keep)
            total = list.sumOf { it[1] - it[0] }
        }

        /** How many bytes from [pos] on are there in one run. */
        fun readyAt(pos: Long): Long = list.firstOrNull { pos >= it[0] && pos < it[1] }?.let { it[1] - pos } ?: 0L

        /** The first byte at or after [pos] that isn't there. */
        fun firstMissing(pos: Long): Long = list.firstOrNull { pos >= it[0] && pos < it[1] }?.get(1) ?: pos
    }

    companion object {
        const val CHUNK = 32L shl 20
        private const val SLACK = 8L shl 20
        private const val SYNC = 8L shl 20
        /** Past this much ahead of the player the download keeps to [CALM_RATE] bytes a second (20 Mbit/s). */
        private const val CALM = 1L shl 30
        private const val CALM_RATE = 2_500_000L
        private val stream: OkHttpClient by lazy { Net.client.newBuilder().cache(null).readTimeout(30, TimeUnit.SECONDS).build() }

        /**
         * Asks the server for the file's size by range; null when it can't do ranges (the player then streams as
         * before). Blocking.
         */
        fun open(dir: File, url: String, headers: Map<String, String>): Prefetch? {
            var why = ""
            val size = runCatching {
                val b = Request.Builder().url(url).header("Range", "bytes=0-0")
                headers.forEach { (k, v) -> b.header(k, v) }
                stream.newCall(b.build()).execute().use { r ->
                    why = "HTTP ${r.code}"
                    if (r.code != 206) null else contentRange(r.header("Content-Range"))?.second
                }
            }.onFailure { why = "$it" }.getOrNull()
            Log.i("homescreen", "prefetch: ${if (size == null) "no ranges ($why)" else "$size bytes"}")
            if (size == null || size <= 0) return null
            // Known before the folder exists, so a [sweep] running meanwhile leaves it.
            synchronized(live) { live += dir }
            dir.mkdirs()
            // Room for the rest of the TV: a couple of gigabytes stay free.
            val room = (dir.usableSpace - (2L shl 30)).coerceAtLeast(0)
            val ahead = minOf(8L shl 30, room * 3 / 4)
            if (ahead < (256L shl 20)) {
                dir.delete()
                synchronized(live) { live -= dir }
                return null
            }
            return Prefetch(url, headers, dir, size, ahead, minOf(room, ahead + (1L shl 30)))
        }

        /** Folders of files this process plays; [sweep] leaves them. */
        private val live = HashSet<File>()

        /** Deletes what a player the system killed left under [root]. Blocking. */
        fun sweep(root: File) {
            root.listFiles()?.forEach { f -> if (synchronized(live) { f !in live }) f.deleteRecursively() }
        }

        /**
         * The chunk to drop: the oldest one behind the player [mine], then the farthest ahead (left from before a
         * seek back); never the one the download [writing] fills.
         */
        internal fun victim(keys: Collection<Long>, mine: Long, writing: Long): Long? =
            keys.filter { it < mine && it != writing }.minOrNull() ?: keys.filter { it > mine && it != writing }.maxOrNull()

        /** Start and total of `bytes a-b/total`; the total is -1 when the server says `*`. */
        internal fun contentRange(h: String?): Pair<Long, Long>? {
            val m = Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(h ?: return null) ?: return null
            return m.groupValues[1].toLong() to (m.groupValues[2].toLongOrNull() ?: -1L)
        }
    }
}
