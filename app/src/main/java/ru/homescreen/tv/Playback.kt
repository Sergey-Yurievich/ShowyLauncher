package ru.homescreen.tv

import ru.homescreen.tv.data.Subs

/**
 * What the built-in player plays. It is handed over in the process rather than in the Intent: an episode's link is
 * asked of Showy only when the episode starts, and every position goes straight into the launcher's history.
 */
class Playback(
    /** The title's history key, under which the player keeps its subtitle choice ([ru.homescreen.tv.data.PlayerPrefs]). */
    val key: String,
    val title: String,
    /** TMDB id, for TheIntroDB ([ru.homescreen.tv.data.Segments]). */
    val tmdbId: Int,
    val serial: Boolean,
    val items: List<Item>,
    val start: Int,
    /** The stream of item i and where to start it; null when the source gives no link. */
    val open: suspend (Int) -> Opened?,
    /** Where item i stopped (position, duration, ended). */
    val report: (Int, Long, Long, Boolean) -> Unit,
    /** How much of item i is watched, 0..1, for the episode cards. */
    val progress: (Int) -> Float,
) {
    /** An episode (or the film); [still] is a full image URL. */
    data class Item(val season: Int, val episode: Int, val name: String?, val still: String?, val runtimeMin: Int)

    /** [subtitles]: the source's own tracks by link, shown beside the file's embedded ones. */
    data class Opened(val url: String, val headers: Map<String, String>, val startMs: Long, val subtitles: List<Subs.Track>)

    companion object {
        /** Set by the launcher before it starts [PlayerActivity]; gone when the process was restarted under the player. */
        @Volatile
        var current: Playback? = null
    }
}
