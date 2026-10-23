package ru.homescreen.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Whether the new episode of a followed series is out in the voice it is watched in, as the source last answered.
 * Keyed by title; [stamp] names what was asked (episode watched, episode aired, source, voice), so an answer about
 * anything else does not count. A "yes" holds until the stamp changes, a "no" for [TTL_NO].
 */
class Releases(context: Context) {
    private val file = File(context.filesDir, "releases.json")
    private val map: JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    /** The last answer for this stamp, and whether it is still fresh; null if never asked. */
    @Synchronized
    fun get(key: String, stamp: String): Pair<Boolean, Boolean>? {
        val a = map.optJSONArray(key) ?: return null
        if (a.optString(0) != stamp) return null
        val ok = a.optInt(1) == 1
        return ok to (ok || System.currentTimeMillis() - a.optLong(2) < TTL_NO)
    }

    @Synchronized
    fun set(key: String, stamp: String, ok: Boolean) {
        map.put(key, JSONArray().put(stamp).put(if (ok) 1 else 0).put(System.currentTimeMillis()))
        write()
    }

    /** Drops the titles no longer followed, so the file stays as small as the list of followed series. */
    @Synchronized
    fun keepOnly(keys: Set<String>) {
        val gone = map.keys().asSequence().filter { it !in keys }.toList()
        if (gone.isEmpty()) return
        gone.forEach { map.remove(it) }
        write()
    }

    private fun write() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(map.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TTL_NO = 3 * 3600_000L
    }
}
