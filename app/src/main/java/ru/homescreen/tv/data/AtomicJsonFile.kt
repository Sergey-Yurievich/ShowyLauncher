package ru.homescreen.tv.data

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * One small JSON file written whole: a temp file synced to disk, then moved over the old one, so a TV unplugged
 * mid-write keeps the last good copy. A file that does not parse is set aside as `<name>.corrupt` instead of being
 * overwritten by the first save, so bookmarks and history can still be recovered by hand.
 *
 * [write] returns at once: the text goes to disk on one background thread, and a burst of saves (a player reporting,
 * a sync merging) costs one write of the latest text.
 */
class AtomicJsonFile(private val file: File) {
    private val pending = AtomicReference<String?>(null)

    /** The file parsed by [parse]; null when there is none, or it is unreadable (then it is set aside). */
    fun <T> read(parse: (String) -> T): T? {
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            Log.w(TAG, "${file.name}: not read", e)
            return null
        }
        return try {
            parse(text)
        } catch (e: Exception) {
            Log.w(TAG, "${file.name}: does not parse, kept as .corrupt", e)
            file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            null
        }
    }

    fun readObject(): JSONObject = read(::JSONObject) ?: JSONObject()

    /** Saves [text] in the background; a newer call before it is written replaces it. */
    fun write(text: String) {
        if (pending.getAndSet(text) == null) IO.execute { pending.getAndSet(null)?.let(::writeNow) }
    }

    /** Saves [text] on the calling thread; for callers already off the main one. Two at once would share the temp file. */
    @Synchronized
    fun writeNow(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.w(TAG, "${file.name}: not written", e)
            tmp.delete()
        }
    }

    /** Removes the file, and any save still waiting. */
    fun delete() {
        pending.set(null)
        IO.execute { file.delete() }
    }

    private companion object {
        const val TAG = "homescreen"
        /** One thread for every file: saves keep their order, and the disk sees one write at a time. */
        val IO = Executors.newSingleThreadExecutor { Thread(it, "json-files").apply { isDaemon = true } }
    }
}
