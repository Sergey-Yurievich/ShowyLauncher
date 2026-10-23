package ru.homescreen.tv.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What was watched through ViMu: per-file positions, the last file of each title for «Продолжить просмотр», and the
 * source / season / voice picked for each title so the next visit opens on them. One small JSON file.
 */
class History(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private var root: JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun obj(name: String): JSONObject = root.optJSONObject(name) ?: JSONObject().also { root.put(name, it) }

    /** [source] is only where the title was last played (for the new-episode check): the screen always opens on Filmix. */
    data class Choice(val season: String, val voice: String, val source: String = "")

    data class Mark(val positionMs: Long, val durationMs: Long, val done: Boolean) {
        val progress: Float get() = if (done) 1f else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    /** The title's last played file, for the «Продолжить просмотр» row. */
    data class Entry(val title: Title, val season: Int, val episode: Int, val label: String, val mark: Mark, val time: Long)

    @Synchronized
    fun choice(key: String): Choice? = obj("choice").optJSONObject(key)?.let {
        Choice(it.optString("season"), it.optString("voice"), it.optString("source"))
    }

    @Synchronized
    fun saveChoice(key: String, c: Choice) {
        obj("choice").put(key, JSONObject().put("season", c.season).put("voice", c.voice).put("source", c.source))
        write()
    }

    @Synchronized
    fun mark(key: String, season: Int, episode: Int): Mark? = obj("marks").optJSONObject("$key|$season|$episode")?.let {
        Mark(it.optLong("p"), it.optLong("d"), it.optBoolean("x"))
    }

    @Synchronized
    fun played(t: Title, season: Int, episode: Int, label: String, positionMs: Long, durationMs: Long, ended: Boolean) {
        val done = ended || (durationMs > 0 && positionMs > durationMs * 0.92)
        obj("marks").put("${t.key}|$season|$episode", JSONObject().put("p", positionMs).put("d", durationMs).put("x", done))
        obj("titles").put(t.key, JSONObject().put("t", System.currentTimeMillis()).put("title", Store.titleToJson(t))
            .put("s", season).put("e", episode).put("l", label).put("p", positionMs).put("d", durationMs).put("x", done))
        write()
    }

    /** The title's «Продолжить» entry as stored, to put back if a playback started here fails. */
    @Synchronized
    fun entry(key: String): JSONObject? = obj("titles").optJSONObject(key)?.let { JSONObject(it.toString()) }

    @Synchronized
    fun restore(key: String, entry: JSONObject?) {
        if (entry == null) obj("titles").remove(key) else obj("titles").put(key, entry)
        write()
    }

    /** Most recent first; films watched to the end drop out, series stay (the next episode is waiting). */
    @Synchronized
    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        val titles = obj("titles")
        titles.keys().forEach { k ->
            val o = titles.optJSONObject(k) ?: return@forEach
            val t = Store.titleFromJson(o.getJSONObject("title"))
            val mark = Mark(o.optLong("p"), o.optLong("d"), o.optBoolean("x"))
            if (t.media == Media.MOVIE && mark.done) return@forEach
            out += Entry(t, o.optInt("s"), o.optInt("e"), o.optString("l"), mark, o.optLong("t"))
        }
        return out.sortedByDescending { it.time }.take(20)
    }

    private fun write() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }
}
