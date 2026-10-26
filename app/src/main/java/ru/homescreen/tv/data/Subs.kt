package ru.homescreen.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

/** Subtitle tracks the sources hand out by link: their languages and kinds by label, their text in UTF-8. */
object Subs {
    data class Track(val label: String, val url: String)

    /** `subtitles: [{label, url}]` of a Lampac file or `call` answer. */
    fun tracks(j: JSONObject): List<Track> = j.optJSONArray("subtitles")?.let(::tracks) ?: emptyList()

    fun tracks(a: JSONArray): List<Track> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        .map { Track(it.optString("label"), it.optString("url")) }.filter { it.url.isNotEmpty() }

    /** "en", "ru" or "uk" from a track's label ("English", "Русские", "eng", "Ukrainian"), else null. */
    fun language(label: String): String? = when {
        EN.containsMatchIn(label) -> "en"
        RU.containsMatchIn(label) -> "ru"
        UK.containsMatchIn(label) -> "uk"
        else -> null
    }

    /** Plain tracks first, then ones for the hard of hearing, forced ones (signs only) last. */
    fun rank(label: String): Int = when {
        FORCED.containsMatchIn(label) -> 2
        SDH.containsMatchIn(label) -> 1
        else -> 0
    }

    private val EN = Regex("англ|english|\\beng?\\b", RegexOption.IGNORE_CASE)
    private val RU = Regex("рус|russian|\\brus?\\b", RegexOption.IGNORE_CASE)
    private val UK = Regex("укр|ukrain|\\bukr?\\b", RegexOption.IGNORE_CASE)
    private val FORCED = Regex("forc|форс|надпис|signs", RegexOption.IGNORE_CASE)
    private val SDH = Regex("\\bsdh\\b", RegexOption.IGNORE_CASE)

    // ---------- Files ----------

    /** A track's text in UTF-8, whatever it came in; null when it did not load. */
    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        val abs = if (url.startsWith("/")) Showy.host + url else url
        runCatching {
            Net.client.newCall(Request.Builder().url(abs).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()?.let(::decode)
            }
        }.getOrNull().also { if (it == null) android.util.Log.w("homescreen", "subs: no file at ${abs.substringBefore('?')}") }
    }

    /** UTF-8, else Windows-1251: older Russian subtitles still come in it. */
    internal fun decode(b: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            String(b, Charset.forName("windows-1251"))
        }
        return text.removePrefix("\uFEFF")
    }
}
