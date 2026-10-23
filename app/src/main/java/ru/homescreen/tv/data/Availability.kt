package ru.homescreen.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Whether Showy has sources for a title, remembered like the plugin's rec_src_cache: a "yes" for 3 days, a "no" for
 * one day (new releases reach the sources later).
 */
class Availability(context: Context) {
    private val file = File(context.filesDir, "playable2.json")
    private val map: JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    @Synchronized
    fun get(key: String): Boolean? {
        val a = map.optJSONArray(key) ?: return null
        val ok = a.optInt(0) == 1
        return if (System.currentTimeMillis() - a.optLong(1) > if (ok) TTL_OK else TTL_NO) null else ok
    }

    @Synchronized
    fun set(key: String, ok: Boolean) {
        map.put(key, JSONArray().put(if (ok) 1 else 0).put(System.currentTimeMillis()))
        if (map.length() > 3000) {
            val oldest = map.keys().asSequence().toList().sortedBy { map.optJSONArray(it)?.optLong(1) ?: 0 }
            oldest.take(map.length() - 2000).forEach { map.remove(it) }
        }
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(map.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    /** Answers from another Showy server do not hold for this one. */
    @Synchronized
    fun clear() {
        map.keys().asSequence().toList().forEach { map.remove(it) }
        file.delete()
    }

    companion object {
        private const val TTL_OK = 3 * 24 * 3600_000L
        private const val TTL_NO = 24 * 3600_000L
    }
}
