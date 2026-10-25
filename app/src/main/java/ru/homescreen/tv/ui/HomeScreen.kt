package ru.homescreen.tv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.homescreen.tv.HomeViewModel
import ru.homescreen.tv.SearchSession
import ru.homescreen.tv.Tab
import ru.homescreen.tv.WatchSession
import ru.homescreen.tv.data.AppTile
import ru.homescreen.tv.data.Apps
import ru.homescreen.tv.data.CollectionTile
import ru.homescreen.tv.data.ContinueTile
import ru.homescreen.tv.data.GenreTile
import ru.homescreen.tv.data.Media
import ru.homescreen.tv.data.Inputs
import ru.homescreen.tv.data.Lampa
import ru.homescreen.tv.data.MoreTile
import ru.homescreen.tv.data.Row
import ru.homescreen.tv.data.RowKind
import ru.homescreen.tv.data.Showy
import ru.homescreen.tv.data.Tile
import ru.homescreen.tv.data.Title
import ru.homescreen.tv.data.TitleTile
import ru.homescreen.tv.data.Tmdb
import ru.homescreen.tv.data.TvInput
import ru.homescreen.tv.data.Vimu

private const val YOUTUBE = "com.google.android.youtube.tv"

@Composable
fun HomeScreen(vm: HomeViewModel, homePresses: Int, inputsPresses: Int, voicePresses: Int) {
    val conf = LocalConfiguration.current
    val scale = conf.screenWidthDp / 1920f
    LocalScaleHolder.px = scale * LocalDensity.current.density
    CompositionLocalProvider(LocalScale provides scale) { Home(vm, homePresses, inputsPresses, voicePresses) }
}

/** Debug switch for profiling on the TV: `settings put global hs_off bg,scrim,pool,hero,rows,top` (and `city`). */
private var OFF: Set<String> = emptySet()

@Composable
private fun Home(vm: HomeViewModel, homePresses: Int, inputsPresses: Int, voicePresses: Int) {
    val nav = remember { Nav() }
    val ctx0 = LocalContext.current
    remember { OFF = (Settings.Global.getString(ctx0.contentResolver, "hs_off") ?: "").split(',').map { it.trim() }.toSet(); DebugOff.set = OFF; 0 }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }

    // Rebuilt only when the data or the tab changes, not on every focus move: the same tile objects let unchanged
    // tiles skip recomposition.
    val rows by remember { androidx.compose.runtime.derivedStateOf { vm.rows(nav.tab) } }
    val featured by remember { androidx.compose.runtime.derivedStateOf { vm.featured(nav.tab) } }
    // Приложения has no hero; the other tabs keep theirs even while the first rows are still loading.
    val hasHero = nav.tab != Tab.APPS
    if (!hasHero && nav.zone == Zone.HERO) nav.zone = Zone.ROWS
    if (nav.row > rows.lastIndex) nav.row = rows.lastIndex.coerceAtLeast(0)
    // A tile taken away (out of «Продолжить», a hidden app) must not leave the focus past the row's end.
    rows.getOrNull(nav.row)?.let { if (nav.col > it.tiles.lastIndex) nav.col = it.tiles.lastIndex.coerceAtLeast(0) }
    val rowsMode = nav.zone == Zone.ROWS || !hasHero
    val rowsTop = if (hasHero) Geo.ROW_TOP else Geo.NO_HERO_TOP
    val curTile: Tile? = rows.getOrNull(nav.row)?.tiles?.getOrNull(nav.col)

    // Home button from anywhere: back to the hero of Главная, overlays closed.
    LaunchedEffect(homePresses) {
        if (homePresses > 0) {
            vm.closeWatch()
            nav.layer = Layer.NONE; nav.tab = Tab.HOME; nav.zone = Zone.HERO; nav.row = 0; nav.col = 0
        }
        focus.requestFocus()
    }

    // An update that could not be installed says why.
    LaunchedEffect(ru.homescreen.tv.Updater.problem) {
        ru.homescreen.tv.Updater.problem?.let { nav.toast = it; ru.homescreen.tv.Updater.problem = null }
    }

    // Linking TVs reports how it went.
    LaunchedEffect(vm.sync.note) {
        vm.sync.note?.let { nav.toast = it; vm.sync.note = null }
    }

    // An actor TMDB knows no films of (or no answer): back to the film's screen, saying why.
    val person = vm.person
    LaunchedEffect(person, person?.loading) {
        if (person != null && !person.loading && person.titles.isEmpty() && nav.layer == Layer.PERSON) {
            nav.layer = Layer.WATCH
            nav.toast = if (person.failed) "TMDB не отвечает" else "Фильмов не нашлось"
        }
    }

    // The remote's Input button, from anywhere: the Inputs menu over Home.
    LaunchedEffect(inputsPresses) {
        if (inputsPresses > 0) {
            vm.closeWatch()
            nav.layer = Layer.INPUTS; nav.inputIdx = 0
        }
    }

    // What the hero shows.
    val heroTitle: Title? = when {
        rowsMode -> when (curTile) {
            is TitleTile -> curTile.title
            is ContinueTile -> curTile.title
            else -> null
        }
        else -> featured.getOrNull(nav.slide % featured.size.coerceAtLeast(1))
    }
    heroTitle?.let { LaunchedEffect(it.key) { delay(150); vm.requestDetails(it) } }
    val hero: HeroData? = when {
        heroTitle != null -> heroOf(heroTitle, vm.details[heroTitle.key], vm.newEpisode(heroTitle))
        curTile is CollectionTile -> HeroData(curTile.key, curTile.name, null, 0.0, emptyList(), null, curTile.caption.ifEmpty { null },
            "Вся подборка рядом на вкладке «${vm.tabOf(curTile.categoryId).label}». Состав обновляется сам.")
        curTile is GenreTile -> HeroData(curTile.key, curTile.name, null, 0.0, listOf(if (curTile.media == Media.TV) "Сериалы" else "Фильмы"), null, null,
            if (curTile.top.isEmpty()) "Самое популярное в жанре." else "Сейчас смотрят: " + curTile.top.joinToString(", ") + ".")
        curTile is AppTile -> HeroData(curTile.key, curTile.app.label, null, 0.0, emptyList(), null, null, "Нажмите OK, чтобы открыть.")
        curTile is MoreTile -> null
        else -> null
    }
    // In the rows the hero follows the focus once it rests: a new hero costs ~30 ms of UI thread on this TV, and doing
    // it on every press of a fast scroll showed as a hitch. Same-title updates (details arriving) go through at once.
    var heroShown by remember { androidx.compose.runtime.mutableStateOf(hero) }
    LaunchedEffect(hero?.key) {
        if (rowsMode && heroShown != null) delay(350)
        heroShown = hero
    }
    androidx.compose.runtime.SideEffect { if (hero?.key == heroShown?.key && hero != heroShown) heroShown = hero }

    val backdrop: String? = when {
        heroTitle != null && heroTitle.backdrop != null -> Tmdb.img(heroTitle.backdrop, "w1280")
        curTile is ContinueTile -> Tmdb.img(vm.details[curTile.title.key]?.backdrop, "w1280") ?: curTile.image
        curTile is CollectionTile -> Tmdb.img(curTile.cover, "w1280")
        curTile is GenreTile -> Tmdb.img(curTile.cover, "w1280")
        else -> null
    }
    // Apps and «Смотреть все» keep the last picture instead of flashing to black.
    // The picture follows the focus once it rests for a moment: scrolling fast does not decode a backdrop per tile.
    var shownBackdrop by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    LaunchedEffect(backdrop) {
        if (backdrop == null) return@LaunchedEffect
        if (shownBackdrop != null) delay(280)
        shownBackdrop = backdrop
        vm.requestAccent(backdrop)
    }
    val accentRaw = shownBackdrop?.let { vm.accents[it] }?.takeIf { it != Color.Unspecified } ?: Color(0xFFE8A050)
    // A State, not a value: only draw code reads it, so the colour fade does not recompose the screen.
    val accent = animateColorAsState(accentRaw, tween(700), label = "accent")

    // A tab opens when the focus rests on it, so sliding across to the buttons on the right does not flip tabs.
    LaunchedEffect(nav.top, nav.zone) {
        if (nav.zone == Zone.TOP && nav.top.ordinal <= TopItem.APPS.ordinal) {
            delay(900)
            nav.switchTab(Tab.entries[nav.top.ordinal])
        }
    }

    // Trailer mode: three still seconds on a title and the interface steps aside.
    LaunchedEffect(nav.tab, nav.zone, nav.row, nav.col, nav.slide, nav.layer) {
        nav.trailer = false
        val eligible = nav.layer == Layer.NONE && heroTitle != null && (nav.zone == Zone.HERO || nav.zone == Zone.ROWS)
        if (eligible) { delay(3000); nav.trailer = true }
    }
    // Carousel: 3 s still + 5 s "trailer", then the next film.
    LaunchedEffect(nav.tab, nav.zone, nav.slide, nav.layer, featured.size) {
        if (nav.zone == Zone.HERO && nav.layer == Layer.NONE && featured.size > 1) {
            delay(8000)
            nav.slide = (nav.slide + 1) % featured.size
        }
    }

    val console = Inputs.console(vm.inputs)
    val actions = Actions(ctx, vm, nav, scope, rows, featured, console?.first)

    // The remote's microphone button, from anywhere: search, listening.
    LaunchedEffect(voicePresses) {
        if (voicePresses > 0) actions.voiceSearch()
    }

    Box(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            // OK acts on release: held down, it opens the long-press menu instead (where there is one).
            if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) return@onPreviewKeyEvent when (e.type) {
                KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount == 0) { nav.okDown = true; nav.okLong = false }
                    else if (nav.okDown && !nav.okLong) { nav.okLong = true; if (actions.longOk()) nav.okDown = false }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (nav.okDown) actions.key(e.key)
                    nav.okDown = false
                    true
                }
                else -> false
            }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key == Key.Back || e.key == Key.Escape
            val r = actions.key(e.key)
            if (ru.homescreen.tv.BuildConfig.DEBUG) android.util.Log.d("homescreen", "key ${e.key} repeat=${e.nativeKeyEvent.repeatCount} -> zone=${nav.zone} row=${nav.row} col=${nav.col} layer=${nav.layer}")
            r
        },
    ) {
        if ("bg" !in OFF) Backdrop(
            if ("img" in OFF) null else shownBackdrop, accent, rowsMode, nav.trailer,
            shiftX = if (rowsMode) -minOf(nav.col, 6) * 7f else 0f,
            shiftY = if (rowsMode) -nav.row * 5f else 0f,
        )
        val row = rows.getOrNull(nav.row)
        val anchor = row?.let { Geo.tileAnchor(it, nav.col) }
        if ("pool" !in OFF) Pool(
            cx = anchor?.x ?: 960f,
            cy = (anchor?.y ?: 0f) + rowsTop - 70f,
            visible = rowsMode && nav.zone == Zone.ROWS && nav.base == Layer.NONE && !nav.trailer && anchor != null,
            accent = accent,
        )
        if ("hero" !in OFF) Hero(heroShown, rowsMode, nav.trailer, nav.zone == Zone.HERO && nav.base == Layer.NONE, featured.size, nav.slide % featured.size.coerceAtLeast(1))
        if ("rows" !in OFF) Rows(rows, nav, rowsMode, rowsTop, nav.trailer, vm, accent)
        if ("top" !in OFF) TopBar(nav, vm.weather, vm.place, console, visible = !nav.trailer || nav.zone == Zone.TOP)
        InputsMenu(nav.layer == Layer.INPUTS, vm.inputs, nav.inputIdx)
        SettingsMenu(nav.layer == Layer.SETTINGS, actions.settings(), vm.server, nav.settingsIdx, ru.homescreen.tv.Updater.available?.version,
            ru.homescreen.tv.Updater.busy, ru.homescreen.tv.Updater.checking, vm.sync.pairing, vm.sync.peers.map { it.name }, vm.hiddenApps.size)
        GridScreen(nav.base == Layer.GRID, vm.gridTitle, vm.grid, nav.gridIdx, vm.gridLoading, accent) { vm.newEpisode(it, short = true) }
        SearchScreen(vm.search, vm.voice, nav.base == Layer.SEARCH)
        WatchScreen(vm.watch, vm.watch?.let { vm.details[it.title.key] }, nav.base == Layer.WATCH,
            bookmarked = vm.watch?.let { vm.isBookmarked(it.title.key) } == true, newEpisode = vm.watch?.let { vm.newEpisode(it.title) }, accent = accent) {
            vm.newEpisode(it, short = true)
        }
        GridScreen(nav.base == Layer.PERSON, person?.person?.name ?: "", person?.titles.orEmpty(), person?.idx ?: 0, person?.loading == true,
            accent, person = person?.person) { vm.newEpisode(it, short = true) }
        ContextMenu(nav.layer == Layer.MENU, nav.menu, nav.menuIdx)
        Toast(nav)
    }
}

/** Remote handling and what OK does on each element. */
private class Actions(
    val ctx: Context, val vm: HomeViewModel, val nav: Nav, val scope: CoroutineScope,
    val rows: List<Row>, val featured: List<Title>, val console: TvInput?,
) {
    fun key(k: Key): Boolean {
        val ok = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
        val back = k == Key.Back || k == Key.Escape
        if (!ok && !back && k != Key.DirectionUp && k != Key.DirectionDown && k != Key.DirectionLeft && k != Key.DirectionRight) return false

        // Any key ends trailer mode; arrows still move at once, only Back is spent on leaving it.
        if (nav.trailer && nav.layer == Layer.NONE) {
            nav.trailer = false
            if (back) return true
        }
        when (nav.layer) {
            Layer.INPUTS -> return inputsKey(k, ok, back)
            Layer.SETTINGS -> return settingsKey(k, ok, back)
            Layer.GRID -> return gridKey(k, ok, back)
            Layer.WATCH -> return watchKey(k, ok, back)
            Layer.SEARCH -> return searchKey(k, ok, back)
            Layer.MENU -> return menuKey(k, ok, back)
            Layer.PERSON -> return personKey(k, ok, back)
            Layer.NONE -> {}
        }
        nav.moving?.let { return moveKey(it, k, ok, back) }
        if (back) {
            when (nav.zone) {
                Zone.ROWS -> if (nav.tab != Tab.APPS) nav.zone = Zone.HERO else toTop()
                Zone.TOP -> if (nav.tab != Tab.APPS) nav.zone = Zone.HERO else nav.zone = Zone.ROWS
                Zone.HERO -> {}
            }
            return true
        }
        return when (nav.zone) {
            Zone.TOP -> topKey(k, ok)
            Zone.HERO -> heroKey(k, ok)
            Zone.ROWS -> rowsKey(k, ok)
        }
    }

    private fun toTop() {
        nav.zone = Zone.TOP
        nav.top = nav.topIndexOf(nav.tab)
    }

    private fun topKey(k: Key, ok: Boolean): Boolean {
        // The console's button is there only while one is plugged in.
        val items = TopItem.entries.filter { it != TopItem.CONSOLE || console != null }
        val i = items.indexOf(nav.top).takeIf { it >= 0 } ?: items.indexOf(TopItem.INPUTS)
        when {
            k == Key.DirectionRight -> nav.top = items[(i + 1).coerceAtMost(items.lastIndex)]
            k == Key.DirectionLeft -> nav.top = items[(i - 1).coerceAtLeast(0)]
            k == Key.DirectionDown -> {
                // Down from a tab opens that tab, even if the focus only just landed on it.
                if (nav.top.ordinal <= TopItem.APPS.ordinal) switchTab(Tab.entries[nav.top.ordinal])
                if (nav.tab != Tab.APPS) nav.zone = Zone.HERO else nav.zone = Zone.ROWS
                nav.kick(scope, 0, 1)
                return true
            }
            k == Key.DirectionUp -> return true
            ok -> { activateTop(); return true }
        }
        return true
    }

    private fun switchTab(t: Tab) = nav.switchTab(t)

    private fun activateTop() {
        when (nav.top) {
            TopItem.HOME, TopItem.MOVIES, TopItem.SERIES, TopItem.APPS -> {
                switchTab(Tab.entries[nav.top.ordinal])
                if (nav.tab != Tab.APPS) nav.zone = Zone.HERO else nav.zone = Zone.ROWS
            }
            TopItem.SEARCH -> search()
            TopItem.BOOKMARKS -> if (vm.openBookmarks()) { nav.gridIdx = 0; nav.layer = Layer.GRID } else nav.toast = "Закладок пока нет"
            TopItem.YOUTUBE -> if (!Apps.launchPackage(ctx, YOUTUBE)) nav.toast = "YouTube не установлен"
            TopItem.CONSOLE -> console?.let { if (!Inputs.open(ctx, it)) nav.toast = "Не удалось переключить вход" }
            TopItem.INPUTS -> { nav.layer = Layer.INPUTS; nav.inputIdx = 0 }
            TopItem.SETTINGS -> {
                nav.layer = Layer.SETTINGS
                // On the update when there is one, else on the server in use.
                val rows = settings()
                nav.settingsIdx = if (ru.homescreen.tv.Updater.available != null) 0 else rows.indexOfFirst { it.server == vm.server }.coerceAtLeast(0)
            }
        }
    }

    private fun search() {
        vm.search.start()
        vm.search.zone = SearchSession.Zone.KEYS
        nav.layer = Layer.SEARCH
    }

    private fun searchKey(k: Key, ok: Boolean, back: Boolean): Boolean {
        val s = vm.search
        if (back) {
            // Back while listening only stops the listening.
            if (vm.voice.listening) vm.voice.stop() else nav.layer = Layer.NONE
            return true
        }
        when (s.zone) {
            SearchSession.Zone.KEYS -> {
                val (i, key) = s.keyAt() ?: return true
                val line = s.rows[s.row]
                when {
                    k == Key.DirectionLeft -> if (i > 0) s.unit = s.keyStart(s.row, i - 1)
                    k == Key.DirectionRight -> if (i < line.lastIndex) s.unit = s.keyStart(s.row, i + 1)
                        else if (s.results.isNotEmpty()) {
                            s.zone = SearchSession.Zone.RESULTS
                            s.idx = s.idx.coerceIn(0, s.results.lastIndex)
                        }
                    k == Key.DirectionUp -> if (s.row > 0) s.row--
                    k == Key.DirectionDown -> if (s.row < s.rows.lastIndex) s.row++
                    ok -> s.press(key) { voice(s) }
                }
            }
            SearchSession.Zone.RESULTS -> {
                val cols = SearchSession.RESULT_COLS
                val n = s.results.size
                when {
                    k == Key.DirectionLeft -> if (s.idx % cols == 0) s.zone = SearchSession.Zone.KEYS else s.idx--
                    k == Key.DirectionRight -> if (s.idx % cols < cols - 1 && s.idx + 1 < n) s.idx++
                    k == Key.DirectionUp -> if (s.idx >= cols) s.idx -= cols
                    k == Key.DirectionDown -> if (s.idx + cols < n) s.idx += cols else if (s.idx / cols < (n - 1) / cols) s.idx = n - 1
                    ok -> s.results.getOrNull(s.idx)?.let { watch(it) }
                }
                s.focusMoved()
            }
        }
        return true
    }

    /** The remote's microphone: search opens as it was left and listens; pressed while listening, it ends the phrase. */
    fun voiceSearch() {
        vm.closeWatch()
        if (nav.layer != Layer.SEARCH) search()
        voice(vm.search)
    }

    /** Voice search in place (VoiceInput): the words fill the search line as they are heard; the mic key again ends it. */
    private fun voice(s: SearchSession) {
        val v = vm.voice
        if (v.listening) { v.finish(); return }
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            (ctx as? ru.homescreen.tv.MainActivity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        v.start(if (s.russian) "ru-RU" else "en-US", heard = { s.type(it) }) { text, error ->
            if (text != null) s.type(text) else if (error != null) nav.toast = error
        }
    }

    private fun heroKey(k: Key, ok: Boolean): Boolean {
        val n = featured.size.coerceAtLeast(1)
        when {
            // One button: Left / Right page through the carousel.
            k == Key.DirectionRight -> nav.slide = (nav.slide + 1) % n
            k == Key.DirectionLeft -> nav.slide = (nav.slide - 1 + n) % n
            k == Key.DirectionUp -> toTop()
            k == Key.DirectionDown -> if (rows.isNotEmpty()) {
                nav.zone = Zone.ROWS; nav.row = 0; nav.col = nav.mem[rows[0].id] ?: 0
                nav.kick(scope, 0, 1)
            }
            ok -> featured.getOrNull(nav.slide % n)?.let { t -> watch(t) }
        }
        return true
    }

    private fun rowsKey(k: Key, ok: Boolean): Boolean {
        val row = rows.getOrNull(nav.row) ?: return true
        if (ok) { activateTile(row, row.tiles.getOrNull(nav.col) ?: return true); return true }
        val c0 = nav.col
        val r0 = nav.row
        if (row.kind == RowKind.APP_GRID) {
            val cols = Geo.GRID_COLS
            val c = nav.col
            when (k) {
                Key.DirectionRight -> if (c % cols < cols - 1 && c + 1 < row.tiles.size) nav.col++
                Key.DirectionLeft -> if (c % cols > 0) nav.col--
                Key.DirectionDown -> if (c + cols < row.tiles.size) nav.col += cols
                Key.DirectionUp -> if (c - cols >= 0) nav.col -= cols else toTop()
                else -> {}
            }
        } else when (k) {
            Key.DirectionRight -> nav.col = (nav.col + 1).coerceAtMost(row.tiles.lastIndex)
            Key.DirectionLeft -> nav.col = (nav.col - 1).coerceAtLeast(0)
            Key.DirectionDown, Key.DirectionUp -> {
                nav.mem[row.id] = nav.col
                if (k == Key.DirectionUp && nav.row == 0) {
                    if (nav.tab != Tab.APPS) nav.zone = Zone.HERO else toTop()
                    return true
                }
                val nr = (nav.row + if (k == Key.DirectionDown) 1 else -1).coerceIn(0, rows.lastIndex)
                nav.row = nr
                nav.col = (nav.mem[rows[nr].id] ?: 0).coerceAtMost(rows[nr].tiles.lastIndex)
            }
            else -> {}
        }
        if (nav.col != c0 || nav.row != r0) nav.kick(scope,
            if (k == Key.DirectionRight) 1 else if (k == Key.DirectionLeft) -1 else 0,
            if (k == Key.DirectionDown) 1 else if (k == Key.DirectionUp) -1 else 0)
        return true
    }

    private fun activateTile(row: Row, tile: Tile) {
        when (tile) {
            is TitleTile -> watch(tile.title)
            // Lampa's own Watch Next entries carry its intent; everything else plays from here.
            is ContinueTile -> if (tile.intentUri != null) { if (!Lampa.resume(ctx, tile)) nav.toast = "Lampa не установлена" } else watch(tile.title)
            is CollectionTile -> {
                val tab = vm.tabOf(tile.categoryId)
                switchTab(tab)
                val idx = vm.rows(tab).indexOfFirst { it.id == tile.categoryId }
                nav.zone = Zone.ROWS; nav.row = idx.coerceAtLeast(0); nav.col = 0
            }
            is AppTile -> if (!Apps.launch(ctx, tile.app.intent)) nav.toast = "Не удалось открыть ${tile.app.label}"
            is MoreTile -> { vm.openGrid(tile.categoryId); nav.gridIdx = 0; nav.layer = Layer.GRID }
            is GenreTile -> { vm.openGrid(tile.categoryId); nav.gridIdx = 0; nav.layer = Layer.GRID }
        }
        if (row.kind == RowKind.WIDE) vm.saveSoon()
    }

    /** A film's screen: its details with the Showy sources under them, over whatever is open; Back returns there. */
    private fun watch(t: Title) {
        val d = vm.details[t.key]
        vm.openWatch(t.copy(poster = t.poster ?: d?.poster, backdrop = t.backdrop ?: d?.backdrop))
        nav.watchFrom = nav.layer
        nav.layer = Layer.WATCH
    }

    private fun watchKey(k: Key, ok: Boolean, back: Boolean): Boolean {
        val w = vm.watch ?: run { nav.layer = nav.watchFrom; return true }
        val picker = w.picker
        if (picker != null) {
            val n = when (picker) {
                WatchSession.Chip.SOURCE -> w.sources.size
                WatchSession.Chip.SEASON -> w.seasons.size
                WatchSession.Chip.VOICE -> w.voices.size
                WatchSession.Chip.TRAILER, WatchSession.Chip.BOOKMARK -> 0
            }
            when {
                back || k == Key.DirectionLeft -> w.picker = null
                k == Key.DirectionDown -> w.pickIdx = (w.pickIdx + 1).coerceAtMost(n - 1)
                k == Key.DirectionUp -> w.pickIdx = (w.pickIdx - 1).coerceAtLeast(0)
                ok -> {
                    w.picker = null
                    when (picker) {
                        WatchSession.Chip.SOURCE -> w.sources.getOrNull(w.pickIdx)?.let { if (it != w.source) w.selectSource(it) }
                        WatchSession.Chip.SEASON -> if (w.pickIdx != w.season) w.selectSeason(w.pickIdx)
                        WatchSession.Chip.VOICE -> if (w.pickIdx != w.voice) w.selectVoice(w.pickIdx)
                        WatchSession.Chip.TRAILER, WatchSession.Chip.BOOKMARK -> {}
                    }
                }
            }
            return true
        }
        if (back) {
            // A title opened from «Похожее» or an actor's films goes back to where it came from.
            if (vm.backFromWatch()) {
                if (vm.person != null) nav.layer = Layer.PERSON
                return true
            }
            vm.closeWatch()
            nav.layer = nav.watchFrom
            fitGrid()
            return true
        }
        val chips = w.chips
        // Before the sources are in (or when they failed) only the pills work: Up reaches them, OK below them retries.
        if (w.phase != WatchSession.Phase.READY && w.zone == WatchSession.Zone.LIST) {
            if (k == Key.DirectionUp) { w.zone = WatchSession.Zone.CHIPS; w.chip = w.chip.coerceIn(0, (chips.size - 1).coerceAtLeast(0)) }
            else if (k == Key.DirectionDown) w.below()?.let { w.zone = it }
            else if (ok) w.retry()
            return true
        }
        when (w.zone) {
            WatchSession.Zone.CHIPS -> when {
                k == Key.DirectionLeft -> w.chip = (w.chip - 1).coerceAtLeast(0)
                k == Key.DirectionRight -> w.chip = (w.chip + 1).coerceAtMost((chips.size - 1).coerceAtLeast(0))
                k == Key.DirectionDown -> if (w.list.isNotEmpty() || w.phase != WatchSession.Phase.READY) w.zone = WatchSession.Zone.LIST
                    else w.below()?.let { w.zone = it }
                ok -> chips.getOrNull(w.chip)?.let { c ->
                    if (c == WatchSession.Chip.TRAILER) { w.trailer()?.let { trailer(it) }; return true }
                    if (c == WatchSession.Chip.BOOKMARK) {
                        vm.toggleBookmark(w.title)
                        nav.toast = if (vm.isBookmarked(w.title.key)) "Добавлено в закладки" else "Убрано из закладок"
                        return true
                    }
                    w.pickIdx = when (c) {
                        WatchSession.Chip.SOURCE -> w.sources.indexOf(w.source)
                        WatchSession.Chip.SEASON -> w.season
                        WatchSession.Chip.VOICE -> w.voice
                        WatchSession.Chip.TRAILER, WatchSession.Chip.BOOKMARK -> 0
                    }.coerceAtLeast(0)
                    w.picker = c
                }
            }
            WatchSession.Zone.LIST -> when {
                k == Key.DirectionUp -> if (w.idx == 0) {
                    w.zone = WatchSession.Zone.CHIPS
                    w.chip = w.chip.coerceIn(0, (chips.size - 1).coerceAtLeast(0))
                } else w.idx--
                k == Key.DirectionDown -> {
                    val below = w.below()
                    if (w.idx >= w.list.lastIndex && below != null) w.zone = below
                    else w.idx = (w.idx + 1).coerceAtMost((w.list.size - 1).coerceAtLeast(0))
                }
                ok -> when {
                    w.files.isNotEmpty() -> if (!Vimu.installed(ctx)) nav.toast = "ViMu не установлен" else w.play(w.idx) { i, done ->
                        (ctx as? ru.homescreen.tv.MainActivity)?.forResult(i, done) ?: false
                    }
                    else -> w.similar.getOrNull(w.idx)?.let { w.selectSimilar(it) }
                }
            }
            WatchSession.Zone.CAST -> when {
                k == Key.DirectionLeft -> w.castIdx = (w.castIdx - 1).coerceAtLeast(0)
                k == Key.DirectionRight -> w.castIdx = (w.castIdx + 1).coerceAtMost(w.cast.lastIndex.coerceAtLeast(0))
                k == Key.DirectionUp -> w.zone = aboveRows(w)
                k == Key.DirectionDown -> if (w.relatedShown) w.zone = WatchSession.Zone.RELATED
                ok -> w.cast.getOrNull(w.castIdx)?.let { vm.openPerson(it); nav.layer = Layer.PERSON }
            }
            WatchSession.Zone.RELATED -> when {
                k == Key.DirectionLeft -> w.relIdx = (w.relIdx - 1).coerceAtLeast(0)
                k == Key.DirectionRight -> w.relIdx = (w.relIdx + 1).coerceAtMost(w.related.lastIndex.coerceAtLeast(0))
                k == Key.DirectionUp -> w.zone = if (w.castShown) WatchSession.Zone.CAST else aboveRows(w)
                ok -> w.related.getOrNull(w.relIdx)?.let { t ->
                    val d = vm.details[t.key]
                    vm.openRelated(t.copy(poster = t.poster ?: d?.poster, backdrop = t.backdrop ?: d?.backdrop))
                }
            }
        }
        return true
    }

    /** Up from the first row under the list: the list, or the pills when there is none. */
    private fun aboveRows(w: WatchSession) =
        if (w.list.isNotEmpty() || w.phase != WatchSession.Phase.READY) WatchSession.Zone.LIST else WatchSession.Zone.CHIPS

    private fun inputsKey(k: Key, ok: Boolean, back: Boolean): Boolean {
        val list = vm.inputs
        when {
            back -> nav.layer = Layer.NONE
            k == Key.DirectionDown -> nav.inputIdx = (nav.inputIdx + 1).coerceAtMost((list.size - 1).coerceAtLeast(0))
            k == Key.DirectionUp -> if (nav.inputIdx == 0) nav.layer = Layer.NONE else nav.inputIdx--
            ok -> list.getOrNull(nav.inputIdx)?.let {
                nav.layer = Layer.NONE
                if (!Inputs.open(ctx, it)) nav.toast = "Не удалось переключить вход"
            }
        }
        return true
    }

    fun settings(): List<SettingsRow> =
        settingsRows(vm.sync.peers.isNotEmpty(), vm.hiddenApps.isNotEmpty())

    /** The gear menu: see [settingsRows]. Linking keeps it open, so its line shows how the search goes. */
    private fun settingsKey(k: Key, ok: Boolean, back: Boolean): Boolean {
        val rows = settings()
        nav.settingsIdx = nav.settingsIdx.coerceIn(0, rows.lastIndex)
        when {
            back -> nav.layer = Layer.NONE
            k == Key.DirectionDown -> nav.settingsIdx = (nav.settingsIdx + 1).coerceAtMost(rows.lastIndex)
            k == Key.DirectionUp -> if (nav.settingsIdx == 0) nav.layer = Layer.NONE else nav.settingsIdx--
            ok -> {
                val r = rows[nav.settingsIdx]
                when (r.kind) {
                    SettingsKind.UPDATE -> if (ru.homescreen.tv.Updater.available != null) {
                        nav.toast = "Скачиваю обновление…"
                        scope.launch { ru.homescreen.tv.Updater.install(ctx) }
                    } else scope.launch {
                        // Found: the line turns into «Обновить до X» under the focus, no toast needed.
                        if (!ru.homescreen.tv.Updater.check()) nav.toast = "GitHub не отвечает, попробуйте позже"
                        else if (ru.homescreen.tv.Updater.available == null) nav.toast = "Это последняя версия"
                    }
                    SettingsKind.SERVER -> {
                        nav.layer = Layer.NONE
                        val s = r.server ?: return true
                        if (s != vm.server) {
                            vm.selectServer(s)
                            nav.toast = "Источник фильмов: ${s.label}"
                        }
                    }
                    SettingsKind.LINK -> vm.sync.pair()
                    SettingsKind.UNLINK -> {
                        nav.settingsIdx--
                        vm.sync.unlink()
                        nav.toast = "Этот ТВ больше не делится историей"
                    }
                    SettingsKind.UNHIDE -> {
                        nav.layer = Layer.NONE
                        vm.showHiddenApps()
                        nav.toast = "Скрытые приложения вернулись"
                    }
                    SettingsKind.ANDROID -> {
                        nav.layer = Layer.NONE
                        Apps.launch(ctx, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        return true
    }

    // ---------- Long press ----------

    /** OK held down: the menu for what has the focus; false when there is none (OK then acts as usual on release). */
    fun longOk(): Boolean {
        if (nav.trailer && nav.layer == Layer.NONE) nav.trailer = false
        if (nav.moving != null) return false
        val items: List<MenuItem>? = when (nav.layer) {
            Layer.NONE -> when (nav.zone) {
                Zone.HERO -> featured.getOrNull(nav.slide % featured.size.coerceAtLeast(1))?.let { titleMenu(it) }
                Zone.ROWS -> rows.getOrNull(nav.row)?.let { row -> row.tiles.getOrNull(nav.col)?.let { tileMenu(it) } }
                Zone.TOP -> null
            }
            Layer.GRID -> vm.grid.getOrNull(nav.gridIdx)?.let { titleMenu(it) }
            Layer.SEARCH -> if (vm.search.zone == SearchSession.Zone.RESULTS) vm.search.results.getOrNull(vm.search.idx)?.let { titleMenu(it) } else null
            Layer.WATCH -> watchMenu()
            Layer.PERSON -> vm.person?.let { g -> g.titles.getOrNull(g.idx)?.let { titleMenu(it) } }
            else -> null
        }
        return nav.openMenu(items.orEmpty())
    }

    private fun titleMenu(t: Title): List<MenuItem> = buildList {
        if (vm.inContinue(t.key)) add(MenuItem("Убрать из «Продолжить»", Icons.Close) { vm.removeFromContinue(t) })
        val on = vm.isBookmarked(t.key)
        add(MenuItem(if (on) "Убрать из закладок" else "В закладки", if (on) Icons.BookmarkOn else Icons.Bookmark) {
            vm.toggleBookmark(t)
            nav.toast = if (on) "Убрано из закладок" else "Добавлено в закладки"
        })
    }

    private fun tileMenu(tile: Tile): List<MenuItem>? = when (tile) {
        is TitleTile -> titleMenu(tile.title)
        // Lampa's own entries are Lampa's to manage.
        is ContinueTile -> if (tile.intentUri == null) titleMenu(tile.title) else null
        is AppTile -> listOf(
            MenuItem("Переместить", Icons.Move) {
                nav.moving = tile.app.pkg
                nav.toast = "Стрелками — на новое место, OK — готово"
            },
            MenuItem("Скрыть", Icons.Hide) {
                vm.hideApp(tile.app.pkg)
                nav.toast = "Скрыто. Вернуть — в меню шестерёнки"
            },
        )
        else -> null
    }

    /** On an episode (or a film's file): watched or not by hand, or every episode down to this one. */
    private fun watchMenu(): List<MenuItem>? {
        val w = vm.watch ?: return null
        if (w.zone == WatchSession.Zone.RELATED) return w.related.getOrNull(w.relIdx)?.let { titleMenu(it) }
        if (w.zone != WatchSession.Zone.LIST || w.picker != null || w.phase != WatchSession.Phase.READY) return null
        val i = w.idx
        val f = w.files.getOrNull(i) ?: return null
        val done = w.mark(f)?.done == true
        return buildList {
            if (!done) add(MenuItem(if (w.serial) "Отметить просмотренной" else "Отметить просмотренным", Icons.Check) { w.setWatched(i, true) })
            // Watched, or started: the mark (and its progress bar) goes.
            if (w.mark(f) != null) add(MenuItem("Снять отметку", Icons.Close) { w.setWatched(i, false) })
            if (w.serial && i > 0) {
                val first = w.files.first().episode
                add(MenuItem(if (first > 0 && f.episode > first) "Отметить серии $first–${f.episode}" else "Отметить все до этой", Icons.Check) {
                    w.setWatched(i, true, upTo = true)
                })
            }
        }
    }

    private fun menuKey(k: Key, ok: Boolean, back: Boolean): Boolean {
        when {
            back -> nav.layer = nav.menuFrom
            k == Key.DirectionDown -> nav.menuIdx = (nav.menuIdx + 1).coerceAtMost(nav.menu.lastIndex)
            k == Key.DirectionUp -> nav.menuIdx = (nav.menuIdx - 1).coerceAtLeast(0)
            ok -> {
                val item = nav.menu.getOrNull(nav.menuIdx)
                nav.layer = nav.menuFrom
                item?.run?.invoke()
                fitGrid()
            }
        }
        return true
    }

    /** An app on the move: arrows carry it (Up / Down too in the Apps grid), OK or Back leave it there. */
    private fun moveKey(pkg: String, k: Key, ok: Boolean, back: Boolean): Boolean {
        val row = rows.getOrNull(nav.row)
        if (ok || back || row == null || nav.zone != Zone.ROWS) {
            nav.moving = null
            vm.saveAppsOrder()
            return true
        }
        val grid = row.kind == RowKind.APP_GRID
        val to = when (k) {
            Key.DirectionLeft -> nav.col - 1
            Key.DirectionRight -> nav.col + 1
            Key.DirectionUp -> if (grid) nav.col - Geo.GRID_COLS else return true
            Key.DirectionDown -> if (grid) nav.col + Geo.GRID_COLS else return true
            else -> return true
        }.coerceIn(0, row.tiles.lastIndex)
        if (to != nav.col) {
            vm.moveApp(pkg, to)
            nav.col = to
        }
        return true
    }

    /** A title taken out of the bookmarks leaves their grid: keep the focus inside it, or leave it once empty. */
    private fun fitGrid() {
        if (nav.layer != Layer.GRID) return
        if (vm.grid.isEmpty()) nav.layer = Layer.NONE else nav.gridIdx = nav.gridIdx.coerceAtMost(vm.grid.lastIndex)
    }

    private fun trailer(key: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")).setPackage(YOUTUBE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!Apps.launch(ctx, i)) nav.toast = "YouTube не установлен"
    }

    private fun gridKey(k: Key, ok: Boolean, back: Boolean): Boolean {
        when {
            back -> nav.layer = Layer.NONE
            ok -> vm.grid.getOrNull(nav.gridIdx)?.let { watch(it) }
            else -> nav.gridIdx = gridMove(nav.gridIdx, vm.grid.size, k)
        }
        vm.gridFocus(nav.gridIdx)
        return true
    }

    /** An actor's films: Back returns to the film's screen, on the actor; a title opens over it. */
    private fun personKey(k: Key, ok: Boolean, back: Boolean): Boolean {
        val g = vm.person ?: run { nav.layer = Layer.WATCH; return true }
        when {
            back -> nav.layer = Layer.WATCH
            ok -> g.titles.getOrNull(g.idx)?.let { t ->
                val d = vm.details[t.key]
                vm.openFromPerson(t.copy(poster = t.poster ?: d?.poster, backdrop = t.backdrop ?: d?.backdrop))
                nav.layer = Layer.WATCH
            }
            else -> g.idx = gridMove(g.idx, g.titles.size, k)
        }
        return true
    }

    /** An arrow in a 7-wide poster grid of [n]: the new focus. Down from a short last line's column goes to its end. */
    private fun gridMove(i: Int, n: Int, k: Key): Int {
        val cols = 7
        return when (k) {
            Key.DirectionRight -> if (i % cols < cols - 1 && i + 1 < n) i + 1 else i
            Key.DirectionLeft -> if (i % cols > 0) i - 1 else i
            Key.DirectionDown -> if (i + cols < n) i + cols else if (i / cols < (n - 1) / cols) n - 1 else i
            Key.DirectionUp -> if (i >= cols) i - cols else i
            else -> i
        }
    }
}
