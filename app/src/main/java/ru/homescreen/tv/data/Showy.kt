package ru.homescreen.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Showy, the online source behind the user's Lampa plugin m.js: a Lampac server. The launcher talks to it directly, so
 * «Смотреть» needs no Lampa: sources → season / voice → files → stream URL for ViMu.
 * The device token comes from the @showybot Telegram bot (a code shown on screen, see [newCode]).
 */
object Showy {
    /**
     * The servers the plugin's links pick (`<host>/m.js`): one Lampac protocol, own hosts and code endpoints. The two
     * PRO ones share a device token (the free client tries both with it); the free one binds its own.
     */
    enum class Server(val label: String, val host: String, private val auth: String, private val check: String, val pro: Boolean) {
        FREE("Бесплатный", "http://showy.online", "http://87.120.126.125:8001", "check_code", false),
        RU("РФ и соседи", "http://showypro.com", "http://showypro.com/api", "check_pro_code", true),
        WORLD("Остальной мир", "http://showy.pro", "http://87.120.126.125:8001", "check_pro_code", true);

        val codeUrl get() = "$auth/get_code/"
        val checkUrl get() = "$auth/$check/"
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 12; Smart TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("showy", Context.MODE_PRIVATE)
        if (uid.isEmpty()) prefs.edit().putString("uid", randomId(8)).apply()
        if (nwsId.isEmpty()) prefs.edit().putString("nws_id", randomId(32)).apply()
    }

    val uid: String get() = prefs.getString("uid", "") ?: ""
    val nwsId: String get() = prefs.getString("nws_id", "") ?: ""

    /** Where films are looked for, picked in the launcher's settings. A new one starts the RCH link afresh. */
    var server: Server
        get() = prefs.getString("server", null)?.let { n -> Server.entries.firstOrNull { it.name == n } } ?: Server.RU
        set(v) {
            prefs.edit().putString("server", v.name).apply()
            Rch.reset()
        }
    val host: String get() = server.host

    var token: String
        get() = prefs.getString(tokenKey, "") ?: ""
        set(v) = prefs.edit().putString(tokenKey, v).apply()
    private val tokenKey get() = if (server.pro) "token" else "token_free"

    /** The server refused: no token, inactive device or no PRO. [msg] is its own wording. */
    class Denied(val msg: String) : Exception(msg)

    // ---------- Device code ----------

    suspend fun newCode(): Int? = post(server.codeUrl, null)?.let { runCatching { JSONObject(it).optInt("code") }.getOrNull() }
        ?.takeIf { it > 0 }

    enum class CodeState { PENDING, OK, EXPIRED }

    /** Polled while the code is on screen; stores the token once the bot has bound it. */
    suspend fun checkCode(code: Int): CodeState {
        val r = post(server.checkUrl, JSONObject().put("code", code).toString()) ?: return CodeState.PENDING
        val j = runCatching { JSONObject(r) }.getOrNull() ?: return CodeState.PENDING
        val t = j.optString("token").takeIf { it.isNotEmpty() && it != "null" }
        return when (j.optString("status")) {
            "success" -> { t?.let { token = it }; CodeState.OK }
            "expired" -> { t?.let { token = it }; CodeState.EXPIRED }
            else -> CodeState.PENDING
        }
    }

    // ---------- Sources ----------

    data class Query(
        val id: Int,
        val serial: Boolean,
        val title: String,
        val originalTitle: String,
        val originalLanguage: String,
        val year: String,
        val imdbId: String = "",
        val kinopoiskId: String = "",
    )

    data class Source(val key: String, val name: String, val url: String, val show: Boolean)

    /** Fills IMDb / Kinopoisk ids the way the plugin does before asking for sources. */
    suspend fun externalIds(q: Query): Query {
        if (q.imdbId.isNotEmpty() && q.kinopoiskId.isNotEmpty()) return q
        val p = buildList {
            add("id=${q.id}")
            add("serial=${if (q.serial) 1 else 0}")
            if (q.imdbId.isNotEmpty()) add("imdb_id=${q.imdbId}")
            if (q.kinopoiskId.isNotEmpty()) add("kinopoisk_id=${q.kinopoiskId}")
        }.joinToString("&")
        val j = get(account("$host/externalids?$p"), 10)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return q
        return q.copy(
            imdbId = j.optString("imdb_id").takeIf { it.isNotEmpty() && it != "null" } ?: q.imdbId,
            kinopoiskId = j.optString("kinopoisk_id").takeIf { it.isNotEmpty() && it != "0" && it != "null" } ?: q.kinopoiskId,
        )
    }

    fun params(q: Query): String = buildList {
        add("id=${q.id}")
        if (q.imdbId.isNotEmpty()) add("imdb_id=${q.imdbId}")
        if (q.kinopoiskId.isNotEmpty()) add("kinopoisk_id=${q.kinopoiskId}")
        add("title=" + enc(q.title))
        add("original_title=" + enc(q.originalTitle))
        add("serial=${if (q.serial) 1 else 0}")
        add("original_language=" + q.originalLanguage)
        add("year=" + q.year.ifEmpty { "0000" })
        add("source=tmdb")
        add("external_ids=true")
        add("clarification=0")
        add("similar=false")
        add("rchtype=apk")
    }.joinToString("&")

    /**
     * The list of sources. The server may answer "life": sources then arrive over ~15 s, and [update] gets each
     * growing list with `ready` true on the last one. Throws [Denied] or [IOException].
     */
    suspend fun sources(q: Query, update: (List<Source>, Boolean) -> Unit) {
        val text = get(account("$host/lite/events?life=true&" + params(q))) ?: throw IOException("events")
        val t = text.trim()
        if (t.startsWith("[")) { update(parseSources(JSONArray(t)), true); return }
        val j = JSONObject(t)
        deny(j)
        if (!j.optBoolean("life")) { update(parseSources(j.optJSONArray("online")), true); return }
        val memkey = j.optString("memkey")
        var last = emptyList<Source>()
        repeat(16) {
            val o = get(account("$host/lifeevents?memkey=$memkey&" + params(q)), 8)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (o != null) {
                deny(o)
                last = parseSources(o.optJSONArray("online"))
                if (o.optBoolean("ready")) { update(last, true); return }
                update(last, false)
            }
            delay(1000)
        }
        update(last, true)
    }

    /** The query for a title: names and ids from TMDB details when there are any. */
    fun query(t: Title, d: Details?) = Query(
        id = t.id,
        serial = t.media == Media.TV,
        title = t.name,
        originalTitle = d?.originalTitle?.ifEmpty { null } ?: t.originalTitle.ifEmpty { t.name },
        originalLanguage = d?.originalLanguage?.ifEmpty { null } ?: t.originalLanguage,
        year = t.year,
        imdbId = d?.imdbId ?: "",
    )

    /**
     * Whether some source really has the title. The sources list is the same for every title, so the first [probe]
     * sources are asked for their page in turn: files or seasons mean yes. No if all of them answered with nothing,
     * null if none answered (or no access).
     */
    suspend fun playable(q: Query, probe: Int = 4): Boolean? {
        var list = emptyList<Source>()
        try {
            sources(q) { l, _ -> list = l }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        }
        val shown = list.filter { it.show }
        if (shown.isEmpty()) return if (list.isEmpty()) null else false
        var answered = false
        for (s in shown.take(probe)) {
            val p = try {
                page(s.url + (if ('?' in s.url) "&" else "?") + params(q), 10)
            } catch (e: Denied) {
                return null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                continue
            }
            answered = true
            if (p.files.isNotEmpty() || p.seasons.isNotEmpty() || p.sameYear(q.year).isNotEmpty()) return true
        }
        return if (answered) false else null
    }

    /**
     * Whether source [sourceKey] has an episode of [season] past [after] in [voice] (the page's own when blank or gone),
     * walking its pages the way the «Смотреть» screen does. Null when it could not be asked.
     */
    suspend fun hasEpisode(q: Query, sourceKey: String, season: Int, after: Int, voice: String): Boolean? {
        var list = emptyList<Source>()
        try {
            sources(q) { l, _ -> list = l }
            val src = list.firstOrNull { it.key == sourceKey } ?: return null
            var page = page(src.url + (if ('?' in src.url) "&" else "?") + params(q), 10)
            var seasonOpened = false
            var voiceOpened = false
            for (step in 0 until 5) {
                if (page.files.isEmpty() && page.similar.isNotEmpty()) {
                    val same = page.sameYear(q.year).singleOrNull() ?: return false
                    page = page(same.url, 10)
                    continue
                }
                if (page.files.isEmpty() && page.seasons.isNotEmpty() && !seasonOpened) {
                    // No such season at the source yet: nothing new there.
                    val s = page.seasons.firstOrNull { Regex("\\d+").find(it.text)?.value?.toIntOrNull() == season } ?: return false
                    seasonOpened = true
                    page = page(s.url, 10)
                    continue
                }
                val want = page.voices.firstOrNull { it.text == voice }
                if (want != null && !want.active && !voiceOpened) {
                    voiceOpened = true
                    page = page(want.url, 10)
                    continue
                }
                return page.files.any { (it.season <= 0 || it.season == season) && it.episode > after }
            }
            return false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        }
    }

    private fun parseSources(a: JSONArray?): List<Source> {
        val out = ArrayList<Source>()
        a.forEachObj { o ->
            val name = o.optString("name")
            val key = (o.optString("balanser").takeIf { it.isNotEmpty() } ?: name.substringBefore(' ')).lowercase()
            out += Source(key, name, o.optString("url"), !o.has("show") || o.optBoolean("show"))
        }
        return out.distinctBy { it.key }
    }

    // ---------- A source's pages ----------

    /** One clickable element of a page: a season or a similar title (`link`), a file (`play` / `call`), a voice. */
    data class Item(
        val method: String,
        val url: String,
        val text: String,
        val season: Int,
        val episode: Int,
        val active: Boolean,
        val similar: Boolean,
        val json: JSONObject,
    ) {
        val details: String get() = json.optString("details")

        /**
         * ShowyTor puts the torrent's facts in voice_name: "2160p / 107.84 GB / HDR10 / H.265 / 68"; a series' torrents
         * (offered to pick from, like search results) carry them in details.
         */
        private val facts: List<String>
            get() = json.optString("voice_name").ifEmpty { details }.split('/').map { it.trim() }.filter { it.isNotEmpty() }

        /** File size in GB when the source tells it (torrents), else null. */
        val sizeGb: Double?
            get() = facts.firstNotNullOfOrNull { f ->
                SIZE.matchEntire(f)?.let { m ->
                    val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let null
                    when (m.groupValues[2].uppercase()) { "TB", "ТБ" -> n * 1024; "MB", "МБ" -> n / 1024; else -> n }
                }
            }

        /** HDR10 / HDR / Dolby Vision from the same facts; plain SDR is not worth a word. */
        val hdr: String? get() = facts.firstOrNull { it.contains("HDR", true) || it.contains("DV", true) || it.contains("Dolby", true) }
        /** "1080p" from the same facts. */
        val resolution: String? get() = facts.firstOrNull { RESOLUTION.matches(it) }
        val year: String get() = json.optString("year").ifEmpty { json.optString("start_date").take(4) }
    }

    data class Page(val items: List<Item>, val voices: List<Item>) {
        val files get() = items.filter { it.method == "play" || it.method == "call" }
        val similar get() = items.filter { it.similar }
        /** Seasons: `link` items that are not search results. */
        val seasons get() = items.filter { it.method == "link" && !it.similar }

        /** Search results from the same year (±1): when the source could not match by id, the title is usually here. */
        fun sameYear(year: String): List<Item> {
            val y = year.toIntOrNull() ?: return emptyList()
            return similar.filter { s -> s.year.toIntOrNull()?.let { kotlin.math.abs(it - y) <= 1 } == true }
        }
    }

    /** A page of a source. Answers that ask for the remote client hub go through [Rch] and are asked again once. */
    suspend fun page(url: String, timeoutSec: Long = 15): Page {
        val text = getHandlingRch(url, timeoutSec) ?: throw IOException("page")
        val t = text.trim()
        if (t.startsWith("{")) runCatching { JSONObject(t) }.getOrNull()?.let { deny(it) }
        return Page(Html.elements(t, "videos__item").map(::item), Html.elements(t, "videos__button").map(::item))
    }

    private suspend fun getHandlingRch(url: String, timeoutSec: Long = 15): String? {
        val u = account(abs(url))
        // The path only: the query carries the account token.
        val path = Uri.parse(u).path
        val text = get(u, timeoutSec) ?: return null
        val j = text.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (j != null && j.optBoolean("rch")) {
            if (!Rch.ensure(j)) { android.util.Log.w("homescreen", "showy page $path: rch not ready"); return null }
            return get(u, timeoutSec).also { android.util.Log.i("homescreen", "showy page $path via rch: ${it?.length}") }
        }
        return text
    }

    private fun item(e: Html.Element): Item {
        val j = runCatching { JSONObject(e.attrs["data-json"] ?: "{}") }.getOrElse { JSONObject() }
        return Item(
            method = j.optString("method"),
            url = j.optString("url"),
            text = e.text,
            // Not every source sets the s / e attributes: then the JSON, then the "3 серия" text.
            season = e.attrs["s"]?.toIntOrNull() ?: j.optInt("s").takeIf { it > 0 } ?: 0,
            episode = e.attrs["e"]?.toIntOrNull() ?: j.optInt("e").takeIf { it > 0 }
                ?: EPISODE.find(e.text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            active = e.classes.contains("active"),
            similar = j.optBoolean("similar"),
            json = j,
        )
    }

    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(GB|TB|MB|ГБ|ТБ|МБ)", RegexOption.IGNORE_CASE)
    private val RESOLUTION = Regex("\\d{3,4}p", RegexOption.IGNORE_CASE)
    private val EPISODE = Regex("^(\\d+)\\s*(серия|эпизод)", RegexOption.IGNORE_CASE)

    data class Stream(val url: String, val quality: String, val headers: Map<String, String>)

    /** The playable URL of a file, in its best quality. `call` files ask the server first. */
    suspend fun stream(item: Item): Stream? {
        val j = if (item.method == "play") item.json else {
            val t = getHandlingRch(item.url) ?: return null
            runCatching { JSONObject(t.trim()) }.getOrNull()?.also { deny(it) } ?: return null
        }
        return fromJson(j)
    }

    /**
     * A URL without asking the server, for the rest of a playlist: `play` files carry it, `call` files have a `stream`
     * link the server redirects to the video. Null when there is neither.
     */
    fun direct(item: Item): String? =
        if (item.method == "play") fromJson(item.json)?.url
        else first(item.json.optString("stream")).takeIf { it.isNotEmpty() }?.let(::playback)

    private fun fromJson(j: JSONObject): Stream? {
        val qualities = LinkedHashMap<String, String>()
        (j.optJSONObject("quality") ?: j.optJSONObject("qualitys"))?.let { q ->
            q.keys().forEach { k -> q.optString(k).takeIf { it.isNotEmpty() }?.let { qualities[k] = first(it) } }
        }
        val best = qualities.entries.maxByOrNull { qualityRank(it.key) }
        val url = best?.value ?: first(j.optString("url")).ifEmpty { first(j.optString("stream")) }
        if (url.isEmpty()) return null
        val headers = HashMap<String, String>()
        j.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        return Stream(playback(url), best?.key ?: "", headers)
    }

    private fun first(url: String) = url.substringBefore(" or ").trim()

    fun qualityRank(q: String): Int {
        val s = q.lowercase()
        if ("4k" in s || "uhd" in s) return 2160
        return Regex("\\d+").find(s)?.value?.toIntOrNull() ?: 0
    }

    /** Server-side control links need the current session, like the plugin's playbackAccount. */
    private fun playback(url: String): String {
        val u = Uri.parse(url)
        return if (u.host == Uri.parse(host).host && (u.path ?: "").startsWith("/lite/")) account(url) else url
    }

    // ---------- HTTP ----------

    private fun deny(j: JSONObject) {
        if (j.optBoolean("accsdb")) throw Denied(j.optString("msg").ifEmpty { "Нет доступа" })
    }

    fun account(url: String): String {
        var u = url
        fun add(k: String, v: String) {
            if (v.isNotEmpty() && !u.contains("$k=")) u += (if ('?' in u) "&" else "?") + k + "=" + enc(v)
        }
        add("uid", uid)
        add("nws_id", nwsId)
        add("showy_token", token)
        return u
    }

    private fun abs(url: String) = if (url.startsWith("/")) host + url else url

    private fun enc(s: String) = Uri.encode(s)

    private fun randomId(n: Int) = (1..n).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    internal suspend fun get(url: String, timeoutSec: Long = 15): String? = withContext(Dispatchers.IO) {
        try {
            val c = Net.client.newBuilder().readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS).build()
            c.newCall(Request.Builder().url(url).header("User-Agent", UA).cacheControl(CacheControl.FORCE_NETWORK).build())
                .execute().use { r -> r.body?.string() }
        } catch (e: Exception) {
            android.util.Log.w("homescreen", "showy get: $e")
            null
        }
    }

    internal suspend fun post(url: String, json: String?): String? = withContext(Dispatchers.IO) {
        try {
            val body = (json ?: "").toRequestBody("application/json".toMediaType())
            Net.client.newCall(Request.Builder().url(url).header("User-Agent", UA).post(body).build())
                .execute().use { r -> r.body?.string() }
        } catch (e: Exception) {
            android.util.Log.w("homescreen", "showy post: $e")
            null
        }
    }
}
