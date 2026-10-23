package ru.homescreen.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One or more TMDB queries shown as one row. Several queries are merged by popularity (premieres + fresh seasons). */
class Feed(val queries: List<Pair<String, Map<String, Any?>>>, val byPopularity: Boolean = false)

class Category(
    val id: String,
    val title: String,
    val main: Feed,
    /** Used when [main] gives fewer than 6 titles (a seasonal keyword with few films, early January). */
    val spare: Feed? = null,
    val limit: Int = 20,
    /** «Смотреть все» goes on with this one once [main] runs out (a network's whole catalogue after its new titles). */
    val more: Feed? = null,
)

/**
 * The plugin's categories (rec.js buildCategories), same queries and filters, so the launcher and «Подборки» show the
 * same rows.
 */
object Catalog {
    private val TV_EXCLUDE = "10767,10763,10764,10762,10766"

    private fun dateShift(days: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + days * 86_400_000L))

    private fun feed(path: String, params: Map<String, Any?> = emptyMap()) = Feed(listOf(path to params))
    private fun discover(params: Map<String, Any?>) = feed("discover/movie", params)
    private fun discoverTv(params: Map<String, Any?>) = feed("discover/tv", params + ("without_genres" to TV_EXCLUDE))
    private fun tvQuery(params: Map<String, Any?>) = "discover/tv" to (params + ("without_genres" to TV_EXCLUDE))

    val year: Int get() = Calendar.getInstance().get(Calendar.YEAR)

    private fun seasonCategory(): Category {
        val month = Calendar.getInstance().get(Calendar.MONTH)
        val pop = "popularity.desc"
        val (title, main, spare) = when {
            // New Year films are the winter row of [yearSeasonCategory]; this one takes winter itself.
            month == 11 || month == 0 -> Triple("Зимние сказки",
                mapOf("with_keywords" to "1442|10794", "sort_by" to pop, "vote_count.gte" to 100),
                mapOf("with_genres" to "10751,14", "sort_by" to pop, "vote_count.gte" to 500))
            month == 1 -> Triple("Ко Дню всех влюблённых",
                mapOf("with_genres" to 10749, "sort_by" to pop, "vote_count.gte" to 500),
                mapOf("with_genres" to 10749, "sort_by" to "vote_average.desc", "vote_count.gte" to 300))
            month in 2..4 -> Triple("Лёгкое и романтичное",
                mapOf("with_genres" to "35,10749", "sort_by" to pop, "vote_count.gte" to 300),
                mapOf("with_genres" to 35, "sort_by" to pop, "vote_count.gte" to 300))
            month in 5..7 -> Triple("Приключения на каникулы",
                mapOf("with_genres" to "12,10751", "sort_by" to pop, "vote_count.gte" to 300),
                mapOf("with_genres" to 12, "sort_by" to pop, "vote_count.gte" to 500))
            month == 8 -> Triple("Снова в школу",
                mapOf("with_keywords" to 6270, "sort_by" to pop, "vote_count.gte" to 200),
                mapOf("with_genres" to "35,10751", "sort_by" to pop, "vote_count.gte" to 500))
            month == 9 -> Triple("Хэллоуин: мурашки по коже",
                mapOf("with_keywords" to 3335, "sort_by" to pop, "vote_count.gte" to 100),
                mapOf("with_genres" to 27, "sort_by" to pop, "vote_count.gte" to 500))
            else -> Triple("Детективы для долгих вечеров",
                mapOf("with_genres" to 9648, "sort_by" to pop, "vote_count.gte" to 500),
                mapOf("with_genres" to "9648|80", "sort_by" to pop, "vote_count.gte" to 300))
        }
        // The spare feed also continues «Смотреть все»: same instance, so the grid stops once it has run through it.
        val spareFeed = discover(spare)
        return Category("season", title, discover(main), spareFeed, more = spareFeed)
    }

    /**
     * Films of the time of year, by TMDB keywords for the season the story happens in: New Year in winter, then
     * spring, summer, autumn. Spare feeds by genre for when a keyword gives too few.
     */
    private fun yearSeasonCategory(): Category {
        val pop = "popularity.desc"
        val (title, main, spare) = when (Calendar.getInstance().get(Calendar.MONTH)) {
            11, 0, 1 -> Triple("Новогоднее кино",
                mapOf("with_keywords" to "207317|252123", "sort_by" to pop, "vote_count.gte" to 100),
                mapOf("with_keywords" to "1442|10794", "sort_by" to pop, "vote_count.gte" to 100))
            2, 3, 4 -> Triple("Весеннее кино",
                mapOf("with_keywords" to 6202, "sort_by" to pop, "vote_count.gte" to 50),
                mapOf("with_genres" to "35,10749", "sort_by" to pop, "vote_count.gte" to 300))
            5, 6, 7 -> Triple("Летнее кино",
                mapOf("with_keywords" to "13088|200129", "sort_by" to pop, "vote_count.gte" to 100),
                mapOf("with_genres" to "35,12", "sort_by" to pop, "vote_count.gte" to 500))
            else -> Triple("Осеннее кино",
                mapOf("with_keywords" to 178883, "sort_by" to pop, "vote_count.gte" to 50),
                mapOf("with_genres" to "18,10749", "sort_by" to pop, "vote_count.gte" to 500))
        }
        val spareFeed = discover(spare)
        return Category("year_season", title, discover(main), spareFeed, more = spareFeed)
    }

    /** The «Жанры» rows: TMDB genre id and name, films and series apart (TMDB has separate genre lists). */
    val MOVIE_GENRES = listOf(
        35 to "Комедии", 28 to "Боевики", 9648 to "Детективы", 53 to "Триллеры", 27 to "Ужасы", 878 to "Фантастика",
        14 to "Фэнтези", 18 to "Драмы", 10749 to "Мелодрамы", 36 to "Исторические", 10752 to "Военные", 80 to "Криминал",
        12 to "Приключения", 16 to "Мультфильмы", 99 to "Документальные",
    )
    val TV_GENRES = listOf(
        9648 to "Детективы", 80 to "Криминал", 35 to "Комедии", 18 to "Драмы", 10759 to "Боевики и приключения",
        10765 to "Фантастика и фэнтези", 16 to "Мультсериалы", 10768 to "Война и политика", 99 to "Документальные",
        10751 to "Семейные",
    )

    fun genreId(media: Media, genre: Int) = (if (media == Media.TV) "gt_" else "gm_") + genre

    private fun genreCategories(): List<Category> =
        MOVIE_GENRES.map { (g, name) ->
            Category(genreId(Media.MOVIE, g), name, discover(mapOf("with_genres" to g, "sort_by" to "popularity.desc", "vote_count.gte" to 300)))
        } + TV_GENRES.map { (g, name) ->
            Category(genreId(Media.TV, g), name, discoverTv(mapOf("with_genres" to g, "sort_by" to "popularity.desc", "vote_count.gte" to 100)))
        }

    private fun network(id: String, title: String, networks: String) = Category(
        id, title,
        Feed(
            listOf(
                tvQuery(mapOf("with_networks" to networks, "first_air_date.gte" to dateShift(-365), "first_air_date.lte" to dateShift(0),
                    "vote_count.gte" to 30, "vote_average.gte" to 6.8, "sort_by" to "popularity.desc")),
                tvQuery(mapOf("with_networks" to networks, "air_date.gte" to dateShift(-60), "air_date.lte" to dateShift(0),
                    "vote_count.gte" to 100, "vote_average.gte" to 7, "sort_by" to "popularity.desc")),
            ),
            byPopularity = true,
        ),
        more = Feed(listOf(tvQuery(mapOf("with_networks" to networks, "vote_count.gte" to 50, "sort_by" to "popularity.desc")))),
    )

    private val lastYearBest get() = discover(mapOf("primary_release_year" to year - 1, "sort_by" to "vote_average.desc", "vote_count.gte" to 500))

    /** Built fresh on each refresh: dates and the seasonal row move with the calendar. */
    fun categories(): Map<String, Category> = listOf(
        Category("trend_all", "Топ-10 недели", feed("trending/all/week"), limit = 10),
        Category("trend_movie", "Тренды недели: кино", feed("trending/movie/week")),
        Category("trend_tv", "Тренды недели: сериалы", feed("trending/tv/week")),
        Category("digital", "Вышло в цифре недавно", discover(mapOf(
            "with_release_type" to 4, "release_date.gte" to dateShift(-60), "release_date.lte" to dateShift(0),
            "sort_by" to "popularity.desc", "vote_count.gte" to 50, "vote_average.gte" to 6)),
            more = discover(mapOf("with_release_type" to 4, "release_date.gte" to dateShift(-365), "release_date.lte" to dateShift(0),
                "sort_by" to "popularity.desc", "vote_count.gte" to 50))),
        Category("new_eps", "Новые серии на этой неделе", discoverTv(mapOf(
            "air_date.gte" to dateShift(-7), "air_date.lte" to dateShift(7), "first_air_date.gte" to dateShift(-365 * 6),
            "vote_count.gte" to 100, "sort_by" to "popularity.desc")),
            more = discoverTv(mapOf("air_date.gte" to dateShift(-60), "air_date.lte" to dateShift(7), "vote_count.gte" to 100,
                "sort_by" to "popularity.desc"))),
        Category("premieres", "Премьеры сериалов этого месяца", discoverTv(mapOf(
            "first_air_date.gte" to dateShift(-30), "first_air_date.lte" to dateShift(0), "vote_count.gte" to 30,
            "sort_by" to "popularity.desc")),
            more = discoverTv(mapOf("first_air_date.gte" to dateShift(-180), "first_air_date.lte" to dateShift(0), "vote_count.gte" to 30,
                "sort_by" to "popularity.desc"))),
        seasonCategory(),
        yearSeasonCategory(),
        network("netflix", "Новое на Netflix", "213"),
        network("hbo", "Новое на HBO", "49|3186"),
        network("apple", "Новое на Apple TV+", "2552"),
        Category("short", "Коротко на вечер: фильмы до 100 минут", discover(mapOf(
            "with_runtime.gte" to 60, "with_runtime.lte" to 100, "vote_average.gte" to 6.5, "vote_count.gte" to 500,
            "sort_by" to "popularity.desc"))),
        Category("mini", "Мини-сериалы на выходные", discoverTv(mapOf("with_type" to 2, "sort_by" to "popularity.desc", "vote_count.gte" to 100))),
        Category("real", "Основано на реальных событиях", discover(mapOf("with_keywords" to 9672, "sort_by" to "popularity.desc", "vote_count.gte" to 300))),
        lastYearBest.let { prev ->
            Category("year_best", "Лучшее за $year год",
                discover(mapOf("primary_release_year" to year, "sort_by" to "vote_average.desc", "vote_count.gte" to 500)), prev, more = prev)
        },
        Category("best5", "Лучшие сериалы за 5 лет", discoverTv(mapOf(
            "first_air_date.gte" to dateShift(-365 * 5), "vote_count.gte" to 1000, "sort_by" to "vote_average.desc")),
            more = discoverTv(mapOf("first_air_date.gte" to dateShift(-365 * 5), "vote_count.gte" to 300, "sort_by" to "vote_average.desc"))),
        Category("family", "Для всей семьи", discover(mapOf("with_genres" to 10751, "sort_by" to "popularity.desc", "vote_count.gte" to 300))),
        Category("hits90", "Хиты 90-х", discover(mapOf(
            "primary_release_date.gte" to "1990-01-01", "primary_release_date.lte" to "1999-12-31", "sort_by" to "vote_count.desc"))),
        Category("hits2000", "Хиты 2000-х", discover(mapOf(
            "primary_release_date.gte" to "2000-01-01", "primary_release_date.lte" to "2009-12-31", "sort_by" to "vote_count.desc"))),
        Category("alltime", "Лучшие фильмы всех времён", discover(mapOf("vote_count.gte" to 5000, "sort_by" to "vote_average.desc"))),
    ).plus(genreCategories()).associateBy { it.id }

    private fun mediaOf(path: String): Media? = when {
        path.contains("/movie") || path.startsWith("movie") -> Media.MOVIE
        path.contains("/tv") || path.startsWith("tv") -> Media.TV
        else -> null
    }

    /** Page [n] of every query of the feed, cleaned and merged; the flag says whether a further page exists. */
    suspend fun page(feed: Feed, n: Int): Pair<List<Title>, Boolean> = coroutineScope {
        // Always pass the page number: without it the CUB proxy serves its own stale cache.
        val parts = feed.queries.map { (path, params) ->
            async { Tmdb.get(path, params + ("page" to n)) to mediaOf(path) }
        }.awaitAll()
        var more = false
        val all = parts.flatMap { (j, media) ->
            if (j != null && j.optInt("total_pages") > n) more = true
            Tmdb.clean(j?.optJSONArray("results"), media)
        }
        (if (feed.byPopularity) all.sortedByDescending { it.popularity } else all) to more
    }

    /** What a row shows: page 1 (from the spare feed if the main one is thin), topped up from page 2 when short. */
    suspend fun load(cat: Category): List<Title> {
        var feed = cat.main
        var (items, more) = page(feed, 1)
        if (items.size < 6 && cat.spare != null) {
            feed = cat.spare
            page(feed, 1).let { items = it.first; more = it.second }
        }
        if (items.size < 12 && more) items = items + page(feed, 2).first
        return items.distinctBy { it.key }.take(cat.limit)
    }

    val GENRES = mapOf(
        28 to "Боевик", 12 to "Приключения", 16 to "Мультфильм", 35 to "Комедия", 80 to "Криминал",
        99 to "Документальный", 18 to "Драма", 10751 to "Семейный", 14 to "Фэнтези", 36 to "История",
        27 to "Ужасы", 10402 to "Музыка", 9648 to "Детектив", 10749 to "Мелодрама", 878 to "Фантастика",
        10770 to "ТВ фильм", 53 to "Триллер", 10752 to "Военный", 37 to "Вестерн",
        10759 to "Боевик и приключения", 10762 to "Детский", 10763 to "Новости", 10764 to "Реалити",
        10765 to "Фантастика и фэнтези", 10766 to "Мыльная опера", 10767 to "Ток-шоу", 10768 to "Война и политика",
    )
}
