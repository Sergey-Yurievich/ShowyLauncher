package ru.homescreen.tv

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ru.homescreen.tv.data.AppInfo
import ru.homescreen.tv.data.AppTile
import ru.homescreen.tv.data.Availability
import ru.homescreen.tv.data.Bookmarks
import ru.homescreen.tv.data.Apps
import ru.homescreen.tv.data.Catalog
import ru.homescreen.tv.data.Category
import ru.homescreen.tv.data.CollIcon
import ru.homescreen.tv.data.CollectionTile
import ru.homescreen.tv.data.ContinueTile
import ru.homescreen.tv.data.Details
import ru.homescreen.tv.data.GenreTile
import ru.homescreen.tv.data.History
import ru.homescreen.tv.data.Inputs
import ru.homescreen.tv.data.Lampa
import ru.homescreen.tv.data.Media
import ru.homescreen.tv.data.MoreTile
import ru.homescreen.tv.data.Place
import ru.homescreen.tv.data.Releases
import ru.homescreen.tv.data.Row
import ru.homescreen.tv.data.Showy
import ru.homescreen.tv.data.RowKind
import ru.homescreen.tv.data.Store
import ru.homescreen.tv.data.Title
import ru.homescreen.tv.data.TitleTile
import ru.homescreen.tv.data.Tmdb
import ru.homescreen.tv.data.TvInput
import ru.homescreen.tv.data.Weather
import ru.homescreen.tv.data.WeatherApi

enum class Tab(val label: String) { HOME("Главная"), MOVIES("Фильмы"), SERIES("Сериалы"), APPS("Приложения") }

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val store = Store(app)
    private val history = History(app)
    private val bookmarkStore = Bookmarks(app)
    private val releases = Releases(app)
    private var categories: Map<String, Category> = Catalog.categories()

    val items = mutableStateMapOf<String, List<Title>>()
    val details = mutableStateMapOf<String, Details>()
    val accents = mutableStateMapOf<String, Color>()
    var continueTiles by mutableStateOf<List<ContinueTile>>(emptyList())
        private set
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set
    var inputs by mutableStateOf<List<TvInput>>(emptyList())
        private set
    var weather by mutableStateOf<Weather?>(null)
        private set
    private val prefs = app.getSharedPreferences("homescreen", android.content.Context.MODE_PRIVATE)
    /** Where the TV is by the network (kept from the last answer, Moscow before any): weather and clock follow it. */
    var place by mutableStateOf(WeatherApi.decode(prefs.getString("place", null)) ?: WeatherApi.MOSCOW)
        private set
    /** Search keeps what was typed between visits. With nothing typed it shows the trending titles. */
    val voice = VoiceInput(app)
    val search = SearchSession(
        viewModelScope,
        popular = { (items["trend_all"].orEmpty() + items["trend_movie"].orEmpty() + items["trend_tv"].orEmpty()).distinctBy { it.key } },
        hasSources = ::hasSources,
    )
    private val availability = Availability(app)

    /** The Showy server films are looked for on, as picked in the settings menu. */
    var server by mutableStateOf(Showy.Server.RU)
        private set

    /** What was learned on the previous server (which titles it has, which new episodes) does not hold on this one. */
    fun selectServer(s: Showy.Server) {
        if (s == Showy.server) return
        Showy.server = s
        server = s
        releasesJob?.cancel()
        availability.clear()
        releases.keepOnly(emptySet())
        inVoice.clear()
        search.sources.clear()
        checkReleases()
    }

    /** Whether Showy can play the title: the cached answer, else a few sources are asked (see [Showy.playable]). */
    private suspend fun hasSources(t: Title): Boolean? {
        availability.get(t.key)?.let { return it }
        if (Showy.token.isEmpty()) return null
        val ok = Showy.playable(Showy.externalIds(Showy.query(t, freshDetails(t))))
        ok?.let { availability.set(t.key, it) }
        return ok
    }

    /** The sources screen of «Смотреть», while it is open. */
    var watch by mutableStateOf<WatchSession?>(null)
        private set
    private var lampaContinue: List<ContinueTile> = emptyList()
    var bookmarks by mutableStateOf(bookmarkStore.all())
        private set
    /** The episode last played of each series in the history, for «Новая серия». */
    private var watchedEpisode: Map<String, Pair<Int, Int>> = emptyMap()
    /** When each followed series last had its details fetched afresh. */
    private val trackedAt = HashMap<String, Long>()
    /** Followed series whose new episode the source already has in the voice they are watched in ([checkReleases]). */
    private val inVoice = mutableStateMapOf<String, Boolean>()
    private var releasesJob: Job? = null
    val grid = mutableStateListOf<Title>()
    var gridTitle by mutableStateOf("")
        private set

    private var started = false
    private var refreshJob: Job? = null

    fun start() {
        if (started) return
        started = true
        server = Showy.server
        viewModelScope.launch {
            // Caches of earlier versions nothing reads any more.
            withContext(Dispatchers.IO) { listOf("playable.json", "sources.json").forEach { java.io.File(ctx.filesDir, it).delete() } }
            store.open()
            for (id in categories.keys) store.row(id)?.let { items[id] = it.first }
            refreshSystem()
            launch { Tmdb.probeImages() }
            refreshCatalog()
            prefetchFeatured()
        }
        // A newer release on GitHub shows in the gear menu; it is installed only when asked.
        viewModelScope.launch {
            while (true) {
                Updater.check()
                delay(12 * 3600_000L)
            }
        }
        viewModelScope.launch {
            var locatedAt = 0L
            while (true) {
                // The city by the network every 6 hours: a TV taken to another home follows.
                if (System.currentTimeMillis() - locatedAt > 6 * 3600_000L) WeatherApi.locate()?.let { p ->
                    locatedAt = System.currentTimeMillis()
                    place = p
                    prefs.edit().putString("place", WeatherApi.encode(p)).apply()
                }
                val w = WeatherApi.now(place)
                if (w != null) weather = w
                // A failed try is repeated soon: right after boot the network may not be up yet.
                delay(if (w == null) 5 * 60_000L else 30 * 60_000L)
            }
        }
    }

    /** Called on every return to Home: cheap, local data only, plus a catalog refresh when it is due. */
    fun onResume() {
        if (!started) return
        viewModelScope.launch { refreshSystem() }
        if (refreshJob?.isActive != true) refreshCatalog()
    }

    private suspend fun refreshSystem() {
        apps = Apps.load(ctx)
        inputs = withContext(Dispatchers.IO) { Inputs.list(ctx) }
        android.util.Log.i("homescreen", "inputs: " + inputs.joinToString { "${it.label}|${it.sub}|${it.connected}|${it.id}" })
        // Lampa's rows from before the launcher played things itself: the plugin's own channel, else Watch Next.
        val (_, fromPlugin) = Lampa.pluginChannel(ctx, "Продолжить просмотр")
        lampaContinue = fromPlugin.ifEmpty { Lampa.continueWatching(ctx) }
        refreshContinue()
    }

    /** What was watched here through ViMu first, then anything Lampa still lists. */
    private fun refreshContinue() {
        val own = history.entries().map { e ->
            val left = if (e.mark.durationMs > e.mark.positionMs) ((e.mark.durationMs - e.mark.positionMs) / 60_000).toInt() else 0
            val label = when {
                e.title.media == Media.TV -> e.label
                left >= 60 -> "осталось ${left / 60} ч ${left % 60} мин"
                left > 0 -> "осталось $left мин"
                else -> ""
            }
            ContinueTile(e.title, null, e.mark.progress, label, null)
        }
        watchedEpisode = history.entries().filter { it.title.media == Media.TV }.associate { it.title.key to (it.season to it.episode) }
        continueTiles = (own + lampaContinue).distinctBy { it.title.key }
        continueTiles.forEach { requestDetails(it.title) }
        refreshFollowed()
    }

    fun isBookmarked(key: String) = bookmarks.any { it.key == key }

    fun toggleBookmark(t: Title) {
        bookmarkStore.toggle(t)
        bookmarks = bookmarkStore.all()
        requestDetails(t)
        refreshFollowed()
        if (gridOfBookmarks) fillBookmarksGrid()
    }

    /**
     * «Новая серия» for a series being watched or bookmarked (others stay quiet), from TMDB's last and next episode.
     * [short] for a poster: just the date of an announced one.
     */
    fun newEpisode(t: Title, short: Boolean = false): String? {
        if (t.media != Media.TV || (t.key !in watchedEpisode && !isBookmarked(t.key))) return null
        val (s, e) = watchedEpisode[t.key] ?: (0 to 0)
        return details[t.key]?.newEpisode(s, e, inVoice[t.key] == true, short)
    }

    /**
     * Fresh TMDB details for each followed series at most every 6 hours, and at once for one just bookmarked or
     * played: the cache keeps air dates for 2 days and may predate them altogether.
     */
    private fun refreshFollowed() {
        val now = System.currentTimeMillis()
        val due = followedSeries().filter { now - (trackedAt[it.key] ?: 0L) >= 6 * 3600_000L }
        if (due.isEmpty()) { checkReleases(); return }
        due.forEach { trackedAt[it.key] = now }
        viewModelScope.launch {
            for (t in due) {
                val d = Tmdb.details(t.media, t.id)
                if (d == null) { trackedAt.remove(t.key); continue }
                details[t.key] = d
                store.saveDetails(t.key, d)
            }
            checkReleases()
        }
    }

    /** Series being watched or bookmarked: the ones «Новая серия» is shown for. */
    private fun followedSeries(): List<Title> =
        (bookmarks + history.entries().map { it.title }).filter { it.media == Media.TV }.distinctBy { it.key }

    /**
     * «Новая серия» waits for the dub: when TMDB says a new episode aired, the source the series was last played in is
     * asked whether it has it in that voice. One series after another; the answers are kept in [releases].
     */
    private fun checkReleases() {
        if (releasesJob?.isActive == true) return
        val series = followedSeries()
        releases.keepOnly(series.mapTo(HashSet()) { it.key })
        releasesJob = viewModelScope.launch {
            for (t in series) {
                val d = details[t.key] ?: continue
                val (s, e) = watchedEpisode[t.key] ?: (0 to 0)
                if (!d.episodeOut(s, e)) { inVoice.remove(t.key); continue }
                val c = history.choice(t.key)
                val source = c?.source?.ifEmpty { null } ?: WatchSession.DEFAULT_SOURCE
                val voice = c?.voice ?: ""
                // Not started: the latest episode itself. Watched this season: anything after it. Else the new season.
                val after = when {
                    s == 0 -> d.lastEpisode - 1
                    d.lastSeason == s -> e
                    else -> 0
                }
                val stamp = "$s|$e|${d.lastSeason}|${d.lastEpisode}|$source|$voice"
                val known = releases.get(t.key, stamp)
                if (known != null) inVoice[t.key] = known.first
                if (known?.second == true || Showy.token.isEmpty()) continue
                val answer = Showy.hasEpisode(Showy.externalIds(Showy.query(t, d)), source, d.lastSeason, after, voice)
                android.util.Log.i("homescreen", "release: ${t.key} s${d.lastSeason} after e$after in $source/$voice -> $answer")
                val ok = answer ?: continue
                releases.set(t.key, stamp, ok)
                inVoice[t.key] = ok
            }
        }
    }

    fun openWatch(t: Title) {
        watch?.close()
        requestDetails(t)
        watch = WatchSession(t, viewModelScope, history, ::freshDetails, { details[t.key] }) { refreshContinue() }.also { it.start() }
    }

    fun closeWatch() {
        watch?.close()
        watch = null
    }

    /** Details with the original title and IMDb id Showy matches by; older cached entries lack them. */
    private suspend fun freshDetails(t: Title): Details? {
        val d = details[t.key]
        if (d != null && d.originalTitle.isNotEmpty()) return d
        val n = Tmdb.details(t.media, t.id) ?: return d
        details[t.key] = n
        store.saveDetails(t.key, n)
        return n
    }

    private fun refreshCatalog() {
        refreshJob = viewModelScope.launch {
            categories = Catalog.categories()
            val gate = Semaphore(3)
            val jobs = ORDER.mapNotNull { id ->
                val cat = categories[id] ?: return@mapNotNull null
                val cached = store.row(id)
                if (cached != null && cached.second < Store.ROWS_REFRESH && items[id] != null) return@mapNotNull null
                launch {
                    gate.withPermit {
                        val fresh = Catalog.load(cat)
                        if (fresh.isNotEmpty()) {
                            items[id] = fresh
                            store.saveRow(id, fresh)
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            store.flush()
        }
    }

    private fun prefetchFeatured() {
        viewModelScope.launch {
            for (tab in listOf(Tab.HOME, Tab.MOVIES, Tab.SERIES)) featured(tab).forEach { requestDetails(it) }
        }
    }

    private val pending = HashSet<String>()

    fun requestDetails(t: Title) {
        val k = t.key
        if (details.containsKey(k) || !pending.add(k)) return
        store.details(k)?.let { details[k] = it; pending.remove(k); return }
        viewModelScope.launch {
            val d = Tmdb.details(t.media, t.id)
            pending.remove(k)
            if (d != null) {
                details[k] = d
                store.saveDetails(k, d)
            }
        }
    }

    /** The dominant colour of a backdrop, for the glow and tint; computed from a small copy, once per image. */
    fun requestAccent(url: String?) {
        if (url == null || accents.containsKey(url)) return
        accents[url] = Color.Unspecified
        viewModelScope.launch {
            val r = ctx.imageLoader.execute(
                ImageRequest.Builder(ctx).data(url).size(96, 54).allowHardware(false).build(),
            )
            val bmp = ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@launch
            val sw = withContext(Dispatchers.Default) {
                val p = Palette.from(bmp).generate()
                p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
            } ?: return@launch
            accents[url] = Color(sw.rgb)
        }
    }

    fun saveSoon() = viewModelScope.launch { store.flush() }

    // ---------- What each tab shows ----------

    fun featured(tab: Tab): List<Title> {
        val id = when (tab) {
            Tab.HOME -> "trend_all"
            Tab.MOVIES -> "trend_movie"
            Tab.SERIES -> "trend_tv"
            Tab.APPS -> return emptyList()
        }
        return items[id].orEmpty().filter { it.backdrop != null }.take(if (tab == Tab.HOME) 6 else 5)
    }

    private fun cat(id: String, kind: RowKind = RowKind.POSTER, score: Boolean = false, more: Boolean = false): Row? {
        val c = categories[id] ?: return null
        val list = items[id].orEmpty()
        if (list.isEmpty()) return null
        val tiles = list.map { TitleTile(it, score = score) } + if (more) listOf(MoreTile(id)) else emptyList()
        return Row(id, c.title, kind, tiles)
    }

    private fun continueRow(media: Media?): Row? {
        val list = continueTiles.filter { media == null || it.title.media == media }
        return if (list.isEmpty()) null else Row("continue", "Продолжить просмотр", RowKind.WIDE, list)
    }

    /** «Жанры»: one low tile per genre, coloured, with the poster of its most popular title. */
    private fun genresRow(media: Media): Row {
        val list = if (media == Media.TV) Catalog.TV_GENRES else Catalog.MOVIE_GENRES
        // The poster changes daily among the genre's five most popular titles; a hit sits on top of several genres, so
        // each tile skips titles a tile before it has used.
        val used = HashSet<String>()
        val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val tiles = list.mapIndexed { i, (g, name) ->
            val id = Catalog.genreId(media, g)
            val titles = items[id].orEmpty()
            val pool = titles.filter { it.poster != null && it.key !in used }.take(5)
            val face = pool.getOrNull(day % pool.size.coerceAtLeast(1)) ?: titles.firstOrNull()
            face?.let { used += it.key }
            GenreTile(id, name, media, (i * 47f) % 360f, face?.poster, face?.backdrop ?: titles.firstOrNull { it.backdrop != null }?.backdrop,
                titles.take(3).map { it.name })
        }
        return Row("genres_" + media.path, "Жанры", RowKind.GENRE, tiles)
    }

    private fun collectionsRow(): Row {
        val tiles = COLLECTIONS.mapNotNull { (id, hue, icon) ->
            val c = categories[id] ?: return@mapNotNull null
            val list = items[id].orEmpty()
            val noun = if (id == "mini") "сериалов" else "фильмов"
            CollectionTile(id, c.title.substringBefore(":"), if (list.isEmpty()) "" else "${list.size} $noun", hue, icon,
                list.firstOrNull { it.backdrop != null }?.backdrop)
        }
        return Row("collections", "Подборки", RowKind.COLLECTION, tiles)
    }

    fun rows(tab: Tab): List<Row> = when (tab) {
        Tab.HOME -> listOfNotNull(
            continueRow(null),
            cat("trend_all"),
            cat("new_eps"),
            collectionsRow(),
            cat("digital", score = true),
            if (apps.isEmpty()) null else Row("apps", "Приложения", RowKind.APP, apps.take(6).map { AppTile(it) }),
        )
        Tab.MOVIES -> listOfNotNull(
            continueRow(Media.MOVIE),
            cat("trend_movie", more = true), genresRow(Media.MOVIE), cat("digital", score = true, more = true),
            cat("season", more = true), cat("year_season", more = true),
            cat("short", more = true), cat("real", more = true), cat("year_best", more = true), cat("family", more = true),
            cat("hits90", more = true), cat("hits2000", more = true), cat("alltime", score = true, more = true),
        )
        Tab.SERIES -> listOfNotNull(
            continueRow(Media.TV),
            cat("trend_tv", more = true), genresRow(Media.TV), cat("new_eps", more = true), cat("premieres", more = true),
            cat("netflix", more = true), cat("hbo", more = true), cat("apple", more = true), cat("mini", more = true),
            cat("best5", score = true, more = true),
        )
        Tab.APPS -> listOf(Row("apps", "Приложения", RowKind.APP_GRID, apps.map { AppTile(it) }))
    }

    /** Which tab holds a category's expanded row (collection cards on Home jump there). */
    fun tabOf(categoryId: String): Tab = if (categoryId in SERIES_CATS) Tab.SERIES else Tab.MOVIES

    // «Смотреть все» pages through the whole feed like the plugin: the next page loads while the focus nears the end.
    private var gridCat: Category? = null
    private var gridFeed: ru.homescreen.tv.data.Feed? = null
    private var gridPage = 0
    private var gridMore = false
    private var gridJob: Job? = null
    private val gridKeys = HashSet<String>()
    var gridLoading by mutableStateOf(false)
        private set

    /** The grid holds the bookmarks (the top bar's icon) rather than a feed: it follows them as they change. */
    private var gridOfBookmarks = false

    fun openGrid(categoryId: String) {
        val c = categories[categoryId] ?: return
        gridJob?.cancel()
        gridOfBookmarks = false
        gridTitle = c.title
        gridCat = c; gridFeed = null; gridPage = 0; gridMore = true
        grid.clear(); gridKeys.clear()
        // The row's titles show at once; page 1 then continues after them without repeats.
        items[categoryId].orEmpty().forEach(::addToGrid)
        gridNext()
    }

    /** «Закладки» in the grid, newest first; false when there are none. */
    fun openBookmarks(): Boolean {
        if (bookmarks.isEmpty()) return false
        gridJob?.cancel()
        gridOfBookmarks = true
        gridTitle = "Закладки"
        gridCat = null; gridMore = false
        fillBookmarksGrid()
        bookmarks.forEach(::requestDetails)
        return true
    }

    private fun fillBookmarksGrid() {
        grid.clear(); gridKeys.clear()
        bookmarks.forEach(::addToGrid)
    }

    /** Called as the focus moves: within two rows of the end, the next page is fetched. */
    fun gridFocus(index: Int) {
        if (index >= grid.size - 14) gridNext()
    }

    private fun addToGrid(t: Title) {
        if (gridKeys.add(t.key)) grid.add(t)
    }

    private fun gridNext() {
        if (gridJob?.isActive == true || !gridMore) return
        val c = gridCat ?: return
        gridJob = viewModelScope.launch {
            gridLoading = true
            try {
                var emptyPages = 0
                while (gridMore) {
                    val n = gridPage + 1
                    // Page 1 decides the feed: the spare one when the main gives fewer than 6 (as in the row).
                    var (list, more) = Catalog.page(gridFeed ?: c.main, n)
                    if (gridFeed == null) {
                        gridFeed = c.main
                        if (list.size < 6 && c.spare != null) {
                            gridFeed = c.spare
                            Catalog.page(c.spare, 1).let { list = it.first; more = it.second }
                        }
                    }
                    gridPage = n
                    gridMore = more
                    // The row's own feed ran out: carry on with the wider one, repeats are dropped as usual.
                    if (!more && c.more != null && gridFeed !== c.more) {
                        gridFeed = c.more
                        gridPage = 0
                        gridMore = true
                    }
                    val before = grid.size
                    list.forEach(::addToGrid)
                    emptyPages = if (grid.size == before) emptyPages + 1 else 0
                    // Keep going while the grid is short or a page brought only repeats, but not forever.
                    if ((grid.size >= 28 && emptyPages == 0) || emptyPages >= 3) break
                }
            } finally {
                gridLoading = false
            }
        }
    }

    companion object {
        /** Refresh order: what Home shows first. */
        private val ORDER = listOf(
            "trend_all", "new_eps", "digital", "trend_movie", "trend_tv", "season", "short", "real", "mini", "family",
            "hits90", "premieres", "netflix", "hbo", "apple", "year_best", "hits2000", "alltime", "best5", "year_season",
        ) + Catalog.MOVIE_GENRES.map { Catalog.genreId(Media.MOVIE, it.first) } + Catalog.TV_GENRES.map { Catalog.genreId(Media.TV, it.first) }
        private val SERIES_CATS = setOf("trend_tv", "new_eps", "premieres", "netflix", "hbo", "apple", "mini", "best5")
        private val COLLECTIONS = listOf(
            Triple("season", 15f, CollIcon.MOON),
            Triple("short", 200f, CollIcon.CLOCK),
            Triple("real", 35f, CollIcon.DOC),
            Triple("mini", 265f, CollIcon.TV),
            Triple("family", 140f, CollIcon.FAMILY),
            Triple("hits90", 320f, CollIcon.STAR),
        )
    }
}
