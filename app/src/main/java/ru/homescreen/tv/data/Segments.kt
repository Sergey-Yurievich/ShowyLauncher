package ru.homescreen.tv.data

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Where an episode's intro, recap and end credits are, from TheIntroDB (theintrodb.org): timestamps people mark for
 * streaming releases, looked up by the TMDB id with no key. A torrent of the same release lines up; a dubbed one may
 * start a few seconds later, so the buttons are a little early or late there.
 */
object Segments {
    data class Marks(val intro: LongRange?, val recap: LongRange?, val credits: Long?)

    private const val API = "https://api.theintrodb.org/v4/media"
    private val cache = ConcurrentHashMap<String, Marks>()

    /** Null when the base knows nothing of the episode (it answers 404) or does not answer. */
    suspend fun of(tmdbId: Int, season: Int?, episode: Int?, durationMs: Long): Marks? {
        val key = "$tmdbId|$season|$episode"
        cache[key]?.let { return it }
        val q = buildString {
            append("$API?tmdb_id=$tmdbId")
            if (season != null && episode != null) append("&season=$season&episode=$episode")
            // Picks the right cut when releases differ in length; the base takes nothing under five minutes.
            if (durationMs >= 300_000) append("&duration_ms=$durationMs")
        }
        val j = Net.json(q) ?: return null
        return parse(j, durationMs).also { cache[key] = it }
    }

    internal fun parse(j: JSONObject, durationMs: Long): Marks {
        fun ranges(name: String): List<LongRange> {
            val a = j.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { s ->
                val start = if (s.isNull("start_ms")) 0L else s.optLong("start_ms")
                val end = if (s.isNull("end_ms")) durationMs.takeIf { it > 0 } ?: return@mapNotNull null else s.optLong("end_ms")
                // Some entries are placeholders (0 to 0); an intro shorter than a few seconds is not worth a button.
                if (end - start < 5_000) null else start..end
            }
        }
        return Marks(
            intro = ranges("intro").firstOrNull(),
            recap = ranges("recap").firstOrNull(),
            credits = credits(j),
        )
    }

    /** Where the end credits start; an open end is fine here, unlike for intros. */
    private fun credits(j: JSONObject): Long? {
        val a = j.optJSONArray("credits") ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .mapNotNull { s -> if (s.isNull("start_ms")) null else s.optLong("start_ms").takeIf { it > 60_000 } }
            .minOrNull()
    }
}
