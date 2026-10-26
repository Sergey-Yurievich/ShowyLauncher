package ru.homescreen.tv.data

import android.content.Intent
import androidx.compose.ui.graphics.ImageBitmap

enum class Media(val path: String) { MOVIE("movie"), TV("tv") }

/** A film or a series as TMDB lists it. Image fields are TMDB paths (`/abc.jpg`), see [Tmdb.img]. */
data class Title(
    val id: Int,
    val media: Media,
    val name: String,
    val year: String,
    val vote: Double,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val genreIds: List<Int>,
    val popularity: Double = 0.0,
    val originalTitle: String = "",
    val originalLanguage: String = "",
) {
    val key: String get() = "${media.path}_$id"
}

/** What one extra TMDB request per title adds: logo, length, age rating, trailer. */
data class Details(
    val logo: String?,
    val backdrop: String? = null,
    val poster: String? = null,
    val runtime: Int,
    val seasons: Int,
    val age: String,
    val trailer: String?,
    val genres: List<String>,
    /** What Showy matches sources by. */
    val originalTitle: String = "",
    val originalLanguage: String = "",
    val imdbId: String = "",
    /** Series: the latest aired and the next announced episode (TMDB last/next_episode_to_air), dates as yyyy-mm-dd. */
    val lastAir: String = "",
    val lastSeason: Int = 0,
    val lastEpisode: Int = 0,
    val nextAir: String = "",
    val nextSeason: Int = 0,
    val nextEpisode: Int = 0,
) {
    /**
     * An episode past the one watched ([season]/[episode], 0/0 when none) aired in the last two weeks: worth asking
     * the source whether it is out in the voice watched (dubs follow the original by up to a week).
     */
    fun episodeOut(season: Int, episode: Int): Boolean {
        val today = java.time.LocalDate.now()
        val last = runCatching { java.time.LocalDate.parse(lastAir) }.getOrNull() ?: return false
        val unseen = lastSeason > season || (lastSeason == season && lastEpisode > episode)
        return unseen && !last.isAfter(today) && last.isAfter(today.minusDays(15))
    }

    /**
     * «Новая серия» for a series being followed: an episode from [episodeOut] that the source has [inVoice], else the
     * date of the next announced one; null when neither.
     */
    fun newEpisode(season: Int, episode: Int, inVoice: Boolean, short: Boolean = false): String? {
        val today = java.time.LocalDate.now()
        if (inVoice && episodeOut(season, episode)) return "Новая серия"
        val next = runCatching { java.time.LocalDate.parse(nextAir) }.getOrNull() ?: return null
        if (next.isBefore(today)) return null
        // On a poster only the date fits: "28 окт.", "завтра".
        val date = when (next) {
            today -> "сегодня"
            today.plusDays(1) -> "завтра"
            else -> next.format(java.time.format.DateTimeFormatter.ofPattern(if (short) "d MMM" else "d MMMM", java.util.Locale("ru")))
        }
        return if (short) date else "Новая серия $date"
    }
}

/** One episode as TMDB describes it (`tv/{id}/season/{n}`); [still] is a TMDB path, see [Tmdb.img]. */
data class Episode(val name: String, val runtime: Int, val vote: Double, val air: String, val still: String? = null)

/** An actor of «В ролях»; [photo] is a TMDB path, see [Tmdb.img]. */
data class Person(val id: Int, val name: String, val photo: String?)

enum class CollIcon { MOON, CLOCK, DOC, TV, FAMILY, STAR }

sealed interface Tile {
    val key: String
}

data class TitleTile(val title: Title, val badge: String? = null, val score: Boolean = false) : Tile {
    override val key: String get() = title.key
}

/** A row from Lampa's «Продолжить просмотр» (Watch Next in the TV provider). */
data class ContinueTile(
    val title: Title,
    val image: String?,
    val progress: Float,
    val label: String,
    val intentUri: String?,
) : Tile {
    override val key: String get() = "cont_" + title.key
}

/** A collection card on the Home tab: opens the matching expanded row on the Фильмы/Сериалы tab. */
data class CollectionTile(
    val categoryId: String,
    val name: String,
    val caption: String,
    val hue: Float,
    val icon: CollIcon,
    val cover: String?,
) : Tile {
    override val key: String get() = "coll_$categoryId"
}

/** A genre on the Фильмы / Сериалы tabs: opens the whole genre as a «Смотреть все» grid. */
data class GenreTile(
    val categoryId: String,
    val name: String,
    val media: Media,
    val hue: Float,
    /** Poster of the genre's most popular title, drawn on the tile; its still is the hero backdrop. */
    val poster: String?,
    val cover: String?,
    val top: List<String>,
) : Tile {
    override val key: String get() = "genre_$categoryId"
}

data class AppTile(val app: AppInfo) : Tile {
    override val key: String get() = "app_" + app.pkg
}

data class MoreTile(val categoryId: String) : Tile {
    override val key: String get() = "more_$categoryId"
}

enum class RowKind { WIDE, POSTER, COLLECTION, GENRE, APP, APP_GRID }

data class Row(val id: String, val title: String, val kind: RowKind, val tiles: List<Tile>)

data class AppInfo(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val intent: Intent,
)

data class TvInput(
    val id: String,
    val label: String,
    val sub: String,
    val connected: Boolean?,
    val isHdmi: Boolean,
    val isTuner: Boolean,
)

/** Where the TV is, as its public IP tells: the city for the weather, the time zone for the clock. */
data class Place(val city: String, val lat: Double, val lon: Double, val zone: String) {
    val zoneId: java.time.ZoneId? get() = runCatching { java.time.ZoneId.of(zone) }.getOrNull()
}

data class Weather(val temp: Int, val code: Int, val isDay: Boolean) {
    /** WMO weather code to a short Russian word. */
    val text: String
        get() = when (code) {
            0 -> "ясно"
            1, 2 -> "малооблачно"
            3 -> "облачно"
            45, 48 -> "туман"
            51, 53, 55, 56, 57 -> "морось"
            61, 63, 65, 66, 67, 80, 81, 82 -> "дождь"
            71, 73, 75, 77, 85, 86 -> "снег"
            95, 96, 99 -> "гроза"
            else -> ""
        }
}
