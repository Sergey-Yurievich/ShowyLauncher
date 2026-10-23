package ru.homescreen.tv.data

import org.json.JSONArray
import org.json.JSONObject
import ru.homescreen.tv.BuildConfig
import java.net.URLEncoder

/**
 * TMDB through the same mirrors the «Подборки» plugin uses: direct TMDB is blocked in Russia, so the CUB proxy goes
 * first and the host that answered is tried first next time.
 */
object Tmdb {
    private val apiHosts = listOf("https://apitmdb.cub.best/3/", "https://api.themoviedb.org/3/")
    private val imageHosts = listOf("https://imagetmdb.com/t/p/", "https://image.tmdb.org/t/p/")
    private const val PROBE = "w92/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"

    @Volatile private var goodApi = 0
    @Volatile private var imageBase = imageHosts[0]

    val hasKey: Boolean get() = BuildConfig.TMDB_KEY.isNotEmpty()

    fun img(path: String?, size: String): String? =
        if (path.isNullOrEmpty()) null else imageBase + size + path

    /** Picks the first image mirror that answers; images then load from it without retries. */
    suspend fun probeImages() {
        for (h in imageHosts) if (Net.reachable(h + PROBE)) { imageBase = h; return }
    }

    suspend fun get(path: String, params: Map<String, Any?> = emptyMap(), language: String = "ru-RU"): JSONObject? {
        if (!hasKey) return null
        val q = StringBuilder("api_key=").append(BuildConfig.TMDB_KEY).append("&language=").append(language)
        for ((k, v) in params) if (v != null) q.append('&').append(k).append('=').append(URLEncoder.encode(v.toString(), "UTF-8"))
        val order = listOf(goodApi) + apiHosts.indices.filter { it != goodApi }
        for (i in order) {
            val j = Net.json(apiHosts[i] + path + "?" + q)
            if (j != null) { goodApi = i; return j }
        }
        return null
    }

    private val TV_EXCLUDE = "10767,10763,10764,10762,10766"

    fun tvExclude(params: Map<String, Any?>): Map<String, Any?> = params + ("without_genres" to TV_EXCLUDE)

    fun parse(m: JSONObject, fallback: Media?): Title? {
        val media = when (m.optString("media_type")) {
            "movie" -> Media.MOVIE
            "tv" -> Media.TV
            "" -> fallback ?: return null
            else -> return null
        }
        val name = m.optString(if (media == Media.TV) "name" else "title").ifEmpty { m.optString("name", m.optString("title")) }
        if (name.isEmpty()) return null
        val date = m.optString(if (media == Media.TV) "first_air_date" else "release_date")
        return Title(
            id = m.optInt("id"),
            media = media,
            name = name,
            year = date.take(4),
            vote = m.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
            overview = m.optString("overview"),
            poster = m.optString("poster_path").takeIf { it.startsWith("/") },
            backdrop = m.optString("backdrop_path").takeIf { it.startsWith("/") },
            genreIds = m.optJSONArray("genre_ids").ints(),
            popularity = m.optDouble("popularity", 0.0).let { if (it.isNaN()) 0.0 else it },
            originalTitle = m.optString(if (media == Media.TV) "original_name" else "original_title"),
            originalLanguage = m.optString("original_language"),
        )
    }

    /** Same filter as the plugin's clean(): has a poster, not Japanese/Chinese/Korean animation. */
    fun clean(results: JSONArray?, fallback: Media?): List<Title> {
        val out = ArrayList<Title>()
        if (results == null) return out
        for (i in 0 until results.length()) {
            val m = results.optJSONObject(i) ?: continue
            if (isAnime(m)) continue
            val t = parse(m, fallback) ?: continue
            if (t.poster == null) continue
            out.add(t)
        }
        return out
    }

    private fun isAnime(m: JSONObject): Boolean {
        val anim = m.optJSONArray("genre_ids").ints().contains(16)
        val lang = m.optString("original_language")
        val asia = lang == "ja" || lang == "zh" || lang == "ko" || lang == "cn"
        val jp = m.optJSONArray("origin_country")?.let { a -> (0 until a.length()).any { a.optString(it) == "JP" } } ?: false
        return anim && (asia || jp)
    }

    private val AGE_US = mapOf(
        "G" to "0+", "PG" to "6+", "PG-13" to "12+", "R" to "18+", "NC-17" to "18+",
        "TV-Y" to "0+", "TV-G" to "0+", "TV-Y7" to "6+", "TV-PG" to "12+", "TV-14" to "16+", "TV-MA" to "18+",
    )

    /**
     * Films and series matching [query], in TMDB's order; people and titles without a poster are left out.
     * Null when TMDB did not answer (as opposed to an empty list: nothing found).
     */
    suspend fun search(query: String): List<Title>? {
        val j = get("search/multi", mapOf("query" to query, "include_adult" to false, "page" to 1)) ?: return null
        val out = ArrayList<Title>()
        j.optJSONArray("results").forEachObj { m -> parse(m, null)?.takeIf { it.poster != null }?.let(out::add) }
        return out.distinctBy { it.key }
    }

    /**
     * «Похожее» under a title's episodes: TMDB's recommendations (what people watch along with it), topped up with its
     * similar titles when there are few. Null when TMDB did not answer.
     */
    suspend fun related(t: Title): List<Title>? {
        val base = "${t.media.path}/${t.id}"
        val rec = get("$base/recommendations", mapOf("page" to 1)) ?: return null
        var list = clean(rec.optJSONArray("results"), t.media)
        if (list.size < 8) get("$base/similar", mapOf("page" to 1))?.let { list = list + clean(it.optJSONArray("results"), t.media) }
        return list.distinctBy { it.key }.filter { it.key != t.key }.take(20)
    }

    /** TMDB's stand-in names ("Эпизод 3", "1 глава", "Episode 3") say nothing the episode number does not. */
    private val GENERIC_EPISODE = Regex("^(\\d+\\s*)?(серия|эпизод|глава|episode|chapter)(\\s*\\d+)?\\.?$", RegexOption.IGNORE_CASE)

    fun genericEpisode(name: String) = name.isBlank() || GENERIC_EPISODE.matches(name.trim())

    /**
     * A season's episodes by number; null when TMDB does not answer. When none has a real Russian name the English
     * names are used ("Эпизод 1" is all TMDB has for many new series).
     */
    suspend fun season(id: Int, n: Int): Map<Int, Episode>? {
        val j = get("tv/$id/season/$n") ?: return null
        val names = HashMap<Int, String>()
        j.optJSONArray("episodes").forEachObj { e -> names[e.optInt("episode_number")] = e.optString("name") }
        if (names.values.all(::genericEpisode)) {
            get("tv/$id/season/$n", language = "en-US")?.optJSONArray("episodes").forEachObj { e ->
                e.optString("name").takeIf { !genericEpisode(it) }?.let { names[e.optInt("episode_number")] = it }
            }
        }
        val out = HashMap<Int, Episode>()
        j.optJSONArray("episodes").forEachObj { e ->
            out[e.optInt("episode_number")] = Episode(
                name = names[e.optInt("episode_number")] ?: e.optString("name"),
                runtime = e.optInt("runtime"),
                vote = e.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
                air = e.optString("air_date"),
            )
        }
        return out
    }

    suspend fun details(media: Media, id: Int): Details? {
        val tv = media == Media.TV
        val j = get(
            media.path + "/" + id,
            mapOf(
                "append_to_response" to if (tv) "images,content_ratings,videos,external_ids" else "images,release_dates,videos",
                "include_image_language" to "ru,en,null",
                "include_video_language" to "ru,en",
            ),
        ) ?: return null
        if (j.optInt("id") == 0) return null

        val runtime = if (tv) j.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else j.optInt("runtime")
        var ru = ""
        var us = ""
        if (tv) {
            j.optJSONObject("content_ratings")?.optJSONArray("results").forEachObj { c ->
                val r = c.optString("rating")
                if (r.isNotEmpty() && c.optString("iso_3166_1") == "RU") ru = r
                if (r.isNotEmpty() && c.optString("iso_3166_1") == "US") us = r
            }
        } else {
            j.optJSONObject("release_dates")?.optJSONArray("results").forEachObj { c ->
                c.optJSONArray("release_dates").forEachObj { d ->
                    val cert = d.optString("certification")
                    if (cert.isNotEmpty() && c.optString("iso_3166_1") == "RU" && ru.isEmpty()) ru = cert
                    if (cert.isNotEmpty() && c.optString("iso_3166_1") == "US" && us.isEmpty()) us = cert
                }
            }
        }
        val age = when {
            ru.isNotEmpty() -> if (ru.endsWith("+")) ru else "$ru+"
            else -> AGE_US[us] ?: ""
        }

        val logos = j.optJSONObject("images")?.optJSONArray("logos")
        fun logo(lang: String): String? {
            var found: String? = null
            logos.forEachObj { l -> if (found == null && l.optString("iso_639_1") == lang) found = l.optString("file_path").takeIf { it.isNotEmpty() } }
            return found
        }

        val vids = ArrayList<JSONObject>()
        j.optJSONObject("videos")?.optJSONArray("results").forEachObj { v ->
            if (v.optString("site") == "YouTube" && v.optString("key").isNotEmpty() &&
                (v.optString("type") == "Trailer" || v.optString("type") == "Teaser")
            ) vids.add(v)
        }
        fun video(lang: String, type: String) =
            vids.firstOrNull { it.optString("iso_639_1") == lang && it.optString("type") == type }?.optString("key")

        val genres = ArrayList<String>()
        j.optJSONArray("genres").forEachObj { g -> g.optString("name").takeIf { it.isNotEmpty() }?.let(genres::add) }

        return Details(
            logo = logo("ru") ?: logo("en"),
            backdrop = j.optString("backdrop_path").takeIf { it.startsWith("/") },
            poster = j.optString("poster_path").takeIf { it.startsWith("/") },
            runtime = runtime,
            seasons = j.optInt("number_of_seasons"),
            age = age,
            trailer = video("ru", "Trailer") ?: video("ru", "Teaser") ?: video("en", "Trailer") ?: video("en", "Teaser")
                ?: vids.firstOrNull()?.optString("key"),
            genres = genres.map { g -> g.replaceFirstChar { it.uppercase() } },
            originalTitle = j.optString(if (tv) "original_name" else "original_title"),
            originalLanguage = j.optString("original_language"),
            imdbId = (if (tv) j.optJSONObject("external_ids")?.optString("imdb_id") else j.optString("imdb_id"))
                ?.takeIf { it.startsWith("tt") } ?: "",
            lastAir = j.optJSONObject("last_episode_to_air")?.optString("air_date") ?: "",
            lastSeason = j.optJSONObject("last_episode_to_air")?.optInt("season_number") ?: 0,
            lastEpisode = j.optJSONObject("last_episode_to_air")?.optInt("episode_number") ?: 0,
            nextAir = j.optJSONObject("next_episode_to_air")?.optString("air_date") ?: "",
            nextSeason = j.optJSONObject("next_episode_to_air")?.optInt("season_number") ?: 0,
            nextEpisode = j.optJSONObject("next_episode_to_air")?.optInt("episode_number") ?: 0,
        )
    }
}

internal fun JSONArray?.ints(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { optInt(it) }

internal inline fun JSONArray?.forEachObj(block: (JSONObject) -> Unit) {
    if (this == null) return
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
