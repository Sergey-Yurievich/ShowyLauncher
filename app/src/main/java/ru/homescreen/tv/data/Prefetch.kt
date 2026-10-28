package ru.homescreen.tv.data

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.common.PlaybackException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A file played from the TV's disk while one connection downloads it ahead of the player, as far as [ahead] bytes past
 * where it reads: a torrent slower than the film still stutters, but less, and a pause lets it fill up. What is there
 * is kept in chunk files of [CHUNK] bytes; past [store] the chunks farthest from the player go. Seeking into what is
 * already down is instant; anywhere else the download starts over from there. Each file has its own folder, which
 * [close] deletes.
 */
@OptIn(UnstableApi::class)
class Prefetch private constructor(
    private val url: String,
    private val headers: Map<String, String>,
    private val dir: File,
    val length: Long,
    private val ahead: Long,
    private val store: Long,
) : Closeable {
    private val lock = Object()
    private val have = Ranges()
    private val chunks = HashMap<Long, FileChannel>()
    /** Where the player last read. */
    private var reader = 0L
    private var failure: IOException? = null
    private var closed = false

    /** The share of the file on disk, 0..1. */
    val loaded: Float get() = synchronized(lock) { have.total.toFloat() / length }

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

    override fun close() {
        synchronized(lock) {
            closed = true
            chunks.values.forEach { runCatching { it.close() } }
            chunks.clear()
            lock.notifyAll()
        }
        dir.deleteRecursively()
    }

    /** Copies up to [len] bytes at [pos], waiting until the download has them. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        val n: Int
        synchronized(lock) {
            reader = pos
            lock.notifyAll()
            while (true) {
                if (closed) throw IOException("closed")
                val ready = have.readyAt(pos)
                if (ready > 0) { n = minOf(len.toLong(), ready, CHUNK - pos % CHUNK).toInt(); break }
                failure?.let { throw it }
                try { lock.wait(500) } catch (e: InterruptedException) { throw InterruptedIOException() }
            }
        }
        val ch = synchronized(lock) { chunks[pos / CHUNK] } ?: throw IOException("chunk gone")
        var done = 0
        while (done < n) {
            val r = ch.read(ByteBuffer.wrap(buf, off + done, n - done), pos % CHUNK + done)
            if (r < 0) throw IOException("short chunk")
            done += r
        }
        return n
    }

    private fun run() {
        var fails = 0
        while (true) {
            val start = synchronized(lock) {
                if (closed) return
                val from = reader
                val s = have.firstMissing(from)
                if (s >= length || s - from > ahead) {
                    try { lock.wait(1000) } catch (e: InterruptedException) { return }
                    null
                } else s
            } ?: continue
            try {
                download(start)
                fails = 0
                synchronized(lock) { failure = null }
            } catch (e: IOException) {
                if (synchronized(lock) { closed }) return
                fails++
                Log.w("homescreen", "prefetch: at $start, try $fails: $e")
                // The player gives up only after the stream has failed for a while: a torrent's peers come and go.
                if (fails >= 6) synchronized(lock) { failure = e; lock.notifyAll() }
                Thread.sleep(minOf(fails * 1000L, 5000L))
            }
        }
    }

    /** Streams from [start] until the player wants something else, the window is full or the stream meets what is there. */
    private fun download(start: Long) {
        val b = Request.Builder().url(url).header("Range", "bytes=$start-")
        headers.forEach { (k, v) -> b.header(k, v) }
        stream.newCall(b.build()).execute().use { r ->
            if (r.code != 206 && !(r.code == 200 && start == 0L)) throw IOException("HTTP ${r.code} at $start")
            val src = r.body?.byteStream() ?: throw IOException("no body")
            val buf = ByteArray(256 * 1024)
            var pos = start
            while (pos < length) {
                synchronized(lock) {
                    if (closed) return
                    val need = have.firstMissing(reader)
                    // Just past this stream (a container skipping a little) it is coming anyway; elsewhere, start over.
                    if (need != pos && !(need > pos && need - pos <= SLACK)) return
                    if (pos - reader > ahead) return
                }
                val n = src.read(buf, 0, minOf(buf.size.toLong(), length - pos, CHUNK - pos % CHUNK).toInt())
                if (n < 0) throw IOException("stream ended at $pos")
                write(pos, buf, n)
                received.addAndGet(n.toLong())
                synchronized(lock) {
                    have.add(pos, pos + n)
                    lock.notifyAll()
                    evict()
                }
                pos += n
            }
        }
    }

    private fun write(pos: Long, buf: ByteArray, n: Int) {
        val ch = synchronized(lock) {
            chunks.getOrPut(pos / CHUNK) { RandomAccessFile(File(dir, "${pos / CHUNK}"), "rw").channel }
        }
        val bb = ByteBuffer.wrap(buf, 0, n)
        var at = pos % CHUNK
        while (bb.hasRemaining()) at += ch.write(bb, at)
    }

    /** Over [store], the chunks farthest from the player go; the one it reads stays. Holds [lock]. */
    private fun evict() {
        while (have.total > store) {
            val mine = reader / CHUNK
            val far = chunks.keys.filter { it != mine }.maxByOrNull { Math.abs(it - mine) } ?: return
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
                    if (r.code != 206) null else r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                }
            }.onFailure { why = "$it" }.getOrNull()
            Log.i("homescreen", "prefetch: ${if (size == null) "no ranges ($why)" else "$size bytes"}")
            if (size == null || size <= 0) return null
            dir.mkdirs()
            // Room for the rest of the TV: a couple of gigabytes stay free.
            val room = (dir.usableSpace - (2L shl 30)).coerceAtLeast(0)
            val ahead = minOf(8L shl 30, room * 3 / 4)
            if (ahead < (256L shl 20)) return null
            return Prefetch(url, headers, dir, size, ahead, minOf(room, ahead + (1L shl 30)))
        }
    }
}
