package ru.homescreen.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Films and series put aside with «В закладки», newest first. One small JSON file: each bookmark with the time it was
 * added, each one taken out with the time it was removed, so [Sync] can merge the lists of linked TVs (later wins).
 */
class Bookmarks(context: Context) {
    private val file = File(context.filesDir, "bookmarks.json")
    private val added = HashMap<String, Pair<Title, Long>>()
    private val removed = HashMap<String, Long>()

    /** Called after every change made here (not after a merge): linked TVs are told. */
    var onChange: (() -> Unit)? = null

    init {
        try {
            if (file.exists()) {
                val text = file.readText()
                if (text.trimStart().startsWith("[")) {
                    // Before 1.6: a plain array, newest first. Its order is kept by spacing the times a second apart.
                    val a = JSONArray(text)
                    val now = System.currentTimeMillis()
                    for (i in 0 until a.length()) Store.titleFromJson(a.getJSONObject(i)).let { added[it.key] = it to now - i * 1000L }
                } else read(JSONObject(text))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun all(): List<Title> = added.values.sortedByDescending { it.second }.map { it.first }

    @Synchronized
    fun has(key: String): Boolean = key in added

    /** Adds the title on top, or takes it out if it is there; true when it is now bookmarked. */
    @Synchronized
    fun toggle(t: Title): Boolean {
        val now = System.currentTimeMillis()
        val had = added.remove(t.key) != null
        if (had) removed[t.key] = now else added[t.key] = t to now
        write()
        onChange?.invoke()
        return !had
    }

    @Synchronized
    fun export(): JSONObject = JSONObject()
        .put("items", JSONArray(added.values.map { (t, time) -> JSONObject().put("title", Store.titleToJson(t)).put("t", time) }))
        .put("removed", JSONObject(removed.mapValues { it.value }))

    /** Takes the newer of each bookmark and each removal; true if the list changed. */
    @Synchronized
    fun merge(other: JSONObject): Boolean {
        val before = added.mapValues { it.value.second }
        read(other)
        val changed = added.mapValues { it.value.second } != before
        if (changed) write()
        return changed
    }

    private fun read(o: JSONObject) {
        o.optJSONObject("removed")?.let { r ->
            r.keys().forEach { k -> val t = r.optLong(k); if (t > (removed[k] ?: -1)) removed[k] = t }
        }
        o.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) {
                val it = a.optJSONObject(i) ?: continue
                val t = Store.titleFromJson(it.optJSONObject("title") ?: continue)
                val time = it.optLong("t")
                if (time > (added[t.key]?.second ?: -1)) added[t.key] = t to time
            }
        }
        // A removal later than the bookmark wins; a bookmark made again after it stays.
        added.keys.filter { (removed[it] ?: -1) >= added.getValue(it).second }.forEach { added.remove(it) }
    }

    private fun write() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(export().toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }
}
