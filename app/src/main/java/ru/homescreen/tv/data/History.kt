package ru.homescreen.tv.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * What was watched through ViMu: per-file positions, the last file of each title for «Продолжить просмотр», and the
 * source / season / voice picked for each title so the next visit opens on them. One small JSON file.
 *
 * Every record carries the time it was written (`t`), and a title taken out of «Продолжить» leaves its time in
 * `removed`: TVs linked by [Sync] merge their histories record by record, the later one wins.
 */
class History(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private var root: JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    /** Called after every change made here (not after a merge): linked TVs are told. */
    var onChange: (() -> Unit)? = null

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
        obj("choice").put(key, JSONObject().put("season", c.season).put("voice", c.voice).put("source", c.source).put("t", now()))
        changed()
    }

    /** An episode marked unwatched by hand keeps its record (`n`), so the unmarking reaches linked TVs too. */
    @Synchronized
    fun mark(key: String, season: Int, episode: Int): Mark? = obj("marks").optJSONObject("$key|$season|$episode")
        ?.takeIf { !it.optBoolean("n") }
        ?.let { Mark(it.optLong("p"), it.optLong("d"), it.optBoolean("x")) }

    @Synchronized
    fun played(t: Title, season: Int, episode: Int, label: String, positionMs: Long, durationMs: Long, ended: Boolean) {
        val done = ended || (durationMs > 0 && positionMs > durationMs * 0.92)
        val now = now()
        obj("marks").put("${t.key}|$season|$episode", JSONObject().put("p", positionMs).put("d", durationMs).put("x", done).put("t", now))
        obj("titles").put(t.key, JSONObject().put("t", now).put("title", Store.titleToJson(t))
            .put("s", season).put("e", episode).put("l", label).put("p", positionMs).put("d", durationMs).put("x", done))
        changed()
    }

    /**
     * Episodes (or a film) marked by hand from the long-press menu. Watched ones count as played to the end, so a
     * series moves on to the next episode; unwatched ones lose their mark and «Продолжить» stays as it was.
     */
    @Synchronized
    fun setWatched(t: Title, files: List<Triple<Int, Int, String>>, watched: Boolean) {
        if (files.isEmpty()) return
        val now = now()
        for ((s, e, _) in files) {
            obj("marks").put("${t.key}|$s|$e",
                if (watched) JSONObject().put("p", 0).put("d", 0).put("x", true).put("t", now) else JSONObject().put("n", true).put("t", now))
        }
        if (watched) {
            val (s, e, label) = files.last()
            obj("titles").put(t.key, JSONObject().put("t", now).put("title", Store.titleToJson(t))
                .put("s", s).put("e", e).put("l", label).put("p", 0).put("d", 0).put("x", true))
        }
        changed()
    }

    /** Out of «Продолжить просмотр»; playing it again brings it back. Marks and choices stay. */
    @Synchronized
    fun remove(key: String) {
        obj("removed").put(key, now())
        changed()
    }

    /** The title's «Продолжить» entry as stored, to put back if a playback started here fails. */
    @Synchronized
    fun entry(key: String): JSONObject? = obj("titles").optJSONObject(key)?.let { JSONObject(it.toString()) }

    /** Restored with a fresh time, so linked TVs that already got the failed start take this back as well. */
    @Synchronized
    fun restore(key: String, entry: JSONObject?) {
        if (entry == null) obj("removed").put(key, now()) else obj("titles").put(key, entry.put("t", now()))
        changed()
    }

    /** Most recent first; films watched to the end drop out, series stay (the next episode is waiting). */
    @Synchronized
    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        val titles = obj("titles")
        val removed = obj("removed")
        titles.keys().forEach { k ->
            val o = titles.optJSONObject(k) ?: return@forEach
            if (removed.optLong(k, -1) >= o.optLong("t")) return@forEach
            val t = Store.titleFromJson(o.optJSONObject("title") ?: return@forEach)
            val mark = Mark(o.optLong("p"), o.optLong("d"), o.optBoolean("x"))
            if (t.media == Media.MOVIE && mark.done) return@forEach
            out += Entry(t, o.optInt("s"), o.optInt("e"), o.optString("l"), mark, o.optLong("t"))
        }
        return out.sortedByDescending { it.time }.take(20)
    }

    // ---------- Linked TVs ----------

    @Synchronized
    fun export(): JSONObject = JSONObject(root.toString())

    /** Takes every record of [other] that is newer than ours; true if anything changed. */
    @Synchronized
    fun merge(other: JSONObject): Boolean {
        var changed = false
        for (name in SECTIONS) {
            val theirs = other.optJSONObject(name) ?: continue
            val mine = obj(name)
            theirs.keys().forEach { k ->
                if (name == "removed") {
                    val t = theirs.optLong(k)
                    if (t > mine.optLong(k, -1)) { mine.put(k, t); changed = true }
                } else {
                    val o = theirs.optJSONObject(k) ?: return@forEach
                    if (name == "titles" && o.optJSONObject("title") == null) return@forEach
                    if (o.optLong("t") > (mine.optJSONObject(k)?.optLong("t") ?: -1)) { mine.put(k, JSONObject(o.toString())); changed = true }
                }
            }
        }
        if (changed) write()
        return changed
    }

    private fun changed() {
        write()
        onChange?.invoke()
    }

    private fun write() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    private companion object {
        val SECTIONS = listOf("marks", "titles", "choice", "removed")
        fun now() = System.currentTimeMillis()
    }
}
