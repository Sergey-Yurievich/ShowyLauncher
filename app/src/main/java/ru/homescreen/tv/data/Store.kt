package ru.homescreen.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Rows and details kept on disk, so the home screen comes up full straight after the TV wakes, and the network only
 * refreshes it. Same policy as the plugin: rows live 6 hours and are refetched in the background after 30 minutes.
 */
class Store(context: Context) {
    private val rowsFile = File(context.filesDir, "rows.json")
    private val detailsFile = File(context.filesDir, "details.json")

    private var rows: JSONObject = JSONObject()
    private val details = LinkedHashMap<String, Pair<Long, Details>>(64, 0.75f, true)

    suspend fun open() = withContext(Dispatchers.IO) {
        rows = read(rowsFile)
        val d = read(detailsFile)
        val now = System.currentTimeMillis()
        d.keys().forEach { k ->
            val o = d.optJSONObject(k) ?: return@forEach
            val t = o.optLong("t")
            if (now - t < DETAILS_TTL) details[k] = t to detailsFromJson(o.getJSONObject("d"))
        }
    }

    /** Cached row and its age in ms, or null. */
    fun row(id: String): Pair<List<Title>, Long>? {
        val o = rows.optJSONObject(id) ?: return null
        val age = System.currentTimeMillis() - o.optLong("t")
        if (age > ROWS_TTL) return null
        val a = o.optJSONArray("items") ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::titleFromJson) } to age
    }

    @Synchronized
    fun saveRow(id: String, items: List<Title>) {
        if (items.isEmpty()) return
        rows.put(id, JSONObject().put("t", System.currentTimeMillis()).put("items", JSONArray(items.map(::titleToJson))))
    }

    fun details(key: String): Details? = details[key]?.second

    @Synchronized
    fun saveDetails(key: String, d: Details) {
        details[key] = System.currentTimeMillis() to d
        while (details.size > DETAILS_MAX) details.remove(details.keys.first())
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        val snapshotRows: String
        val snapshotDetails: String
        synchronized(this@Store) {
            snapshotRows = rows.toString()
            val d = JSONObject()
            for ((k, v) in details) d.put(k, JSONObject().put("t", v.first).put("d", detailsToJson(v.second)))
            snapshotDetails = d.toString()
        }
        write(rowsFile, snapshotRows)
        write(detailsFile, snapshotDetails)
    }

    private fun read(f: File): JSONObject = try {
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun write(f: File, text: String) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ROWS_TTL = 6 * 3600_000L
        const val ROWS_REFRESH = 30 * 60_000L
        private const val DETAILS_TTL = 2 * 24 * 3600_000L
        private const val DETAILS_MAX = 400

        fun titleToJson(t: Title): JSONObject = JSONObject()
            .put("id", t.id).put("m", t.media.path).put("n", t.name).put("y", t.year).put("v", t.vote)
            .put("o", t.overview).put("p", t.poster ?: "").put("b", t.backdrop ?: "")
            .put("g", JSONArray(t.genreIds)).put("pop", t.popularity).put("ot", t.originalTitle).put("ol", t.originalLanguage)

        fun titleFromJson(o: JSONObject): Title = Title(
            id = o.optInt("id"),
            media = if (o.optString("m") == "tv") Media.TV else Media.MOVIE,
            name = o.optString("n"),
            year = o.optString("y"),
            vote = o.optDouble("v", 0.0),
            overview = o.optString("o"),
            poster = o.optString("p").ifEmpty { null },
            backdrop = o.optString("b").ifEmpty { null },
            genreIds = o.optJSONArray("g").ints(),
            popularity = o.optDouble("pop", 0.0),
            originalTitle = o.optString("ot"),
            originalLanguage = o.optString("ol"),
        )

        private fun detailsToJson(d: Details): JSONObject = JSONObject()
            .put("logo", d.logo ?: "").put("bd", d.backdrop ?: "").put("ps", d.poster ?: "").put("rt", d.runtime).put("s", d.seasons).put("age", d.age)
            .put("tr", d.trailer ?: "").put("g", JSONArray(d.genres))
            .put("ot", d.originalTitle).put("ol", d.originalLanguage).put("imdb", d.imdbId)
            .put("la", d.lastAir).put("ls", d.lastSeason).put("le", d.lastEpisode)
            .put("na", d.nextAir).put("ns", d.nextSeason).put("ne", d.nextEpisode)

        private fun detailsFromJson(o: JSONObject): Details = Details(
            logo = o.optString("logo").ifEmpty { null },
            backdrop = o.optString("bd").ifEmpty { null },
            poster = o.optString("ps").ifEmpty { null },
            runtime = o.optInt("rt"),
            seasons = o.optInt("s"),
            age = o.optString("age"),
            trailer = o.optString("tr").ifEmpty { null },
            genres = o.optJSONArray("g")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            originalTitle = o.optString("ot"),
            originalLanguage = o.optString("ol"),
            imdbId = o.optString("imdb"),
            lastAir = o.optString("la"),
            lastSeason = o.optInt("ls"),
            lastEpisode = o.optInt("le"),
            nextAir = o.optString("na"),
            nextSeason = o.optInt("ns"),
            nextEpisode = o.optInt("ne"),
        )
    }
}
