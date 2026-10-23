package ru.homescreen.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import ru.homescreen.tv.HomeViewModel
import ru.homescreen.tv.data.AppTile
import ru.homescreen.tv.data.CollIcon
import ru.homescreen.tv.data.CollectionTile
import ru.homescreen.tv.data.ContinueTile
import ru.homescreen.tv.data.GenreTile
import ru.homescreen.tv.data.MoreTile
import ru.homescreen.tv.data.Row
import ru.homescreen.tv.data.RowKind
import ru.homescreen.tv.data.Tile
import ru.homescreen.tv.data.TitleTile
import ru.homescreen.tv.data.Tmdb

/** Geometry on the 1920 grid; HomeScreen uses the same numbers to place the focus glow. */
object Geo {
    const val ROW_TOP = 650f
    const val HERO_ROW_TOP = 838f
    const val NO_HERO_TOP = 470f
    const val TITLE = 88f
    const val GAP = 30f
    const val AFTER = 84f
    const val GRID_COLS = 5
    const val GRID_GAP_Y = 44f
    const val LEFT = 96f

    fun w(kind: RowKind) = when (kind) {
        RowKind.WIDE, RowKind.COLLECTION -> 420f
        RowKind.POSTER -> 200f
        RowKind.GENRE -> 340f
        RowKind.APP, RowKind.APP_GRID -> 320f
    }

    fun h(kind: RowKind) = when (kind) {
        RowKind.WIDE, RowKind.COLLECTION -> 236f
        RowKind.POSTER -> 300f
        RowKind.GENRE -> 150f
        RowKind.APP, RowKind.APP_GRID -> 180f
    }

    fun lines(row: Row) = if (row.kind == RowKind.APP_GRID) (row.tiles.size + GRID_COLS - 1) / GRID_COLS else 1
    fun tilesHeight(row: Row) = lines(row) * h(row.kind) + (lines(row) - 1) * GRID_GAP_Y
    fun height(row: Row) = TITLE + tilesHeight(row) + AFTER

    /** Horizontal scroll that keeps the focused tile second from the left, never past the row's end. */
    fun shift(row: Row, c: Int): Float {
        if (row.kind == RowKind.APP_GRID) return 0f
        val step = w(row.kind) + GAP
        val max = (row.tiles.size * step - (1920f - LEFT * 2) + GAP).coerceAtLeast(0f)
        return ((c - 1) * step).coerceIn(0f, max)
    }

    /** Centre-bottom of tile [c] relative to its row's top-left, for the glow under it. */
    fun tileAnchor(row: Row, c: Int): Offset = if (row.kind == RowKind.APP_GRID) {
        Offset(LEFT + (c % GRID_COLS) * (w(row.kind) + GAP) + w(row.kind) / 2, TITLE + (c / GRID_COLS) * (h(row.kind) + GRID_GAP_Y) + h(row.kind))
    } else {
        Offset(LEFT + c * (w(row.kind) + GAP) - shift(row, c) + w(row.kind) / 2, TITLE + h(row.kind))
    }
}

@Composable
fun Rows(
    rows: List<Row>, nav: Nav, rowsMode: Boolean, top: Float, trailer: Boolean,
    vm: HomeViewModel, accent: State<Color>,
) {
    if (rows.isEmpty()) return
    val r = nav.row.coerceIn(0, rows.lastIndex)
    val ys = remember(rows) { rows.runningFold(0f) { acc, row -> acc + Geo.height(row) } }
    val target = if (rowsMode) top - ys[r] else Geo.HERO_ROW_TOP
    val y by animateFloatAsState(target, tween(550, easing = FastOutSlowInEasing), label = "rowsY")
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = y * LocalScaleHolder.px }) {
        for (i in rows.indices) {
            val state = when {
                !rowsMode -> if (i == 0) RowState.CUR else RowState.HIDDEN
                i < r -> if (i == r - 1) RowState.PAST else RowState.HIDDEN
                i == r -> RowState.CUR
                i == r + 1 -> RowState.NEXT
                else -> RowState.HIDDEN
            }
            if (state == RowState.HIDDEN) continue
            androidx.compose.runtime.key(rows[i].id) {
                RowView(
                    rows[i], ys[i], state, rowsMode, trailer,
                    focusedCol = if (rowsMode && i == r && nav.zone == Zone.ROWS && nav.layer == Layer.NONE) nav.col else -1,
                    col = if (i == r) nav.col else nav.mem[rows[i].id] ?: 0,
                    nav = nav, vm = vm, accent = accent,
                )
            }
        }
    }
}

private enum class RowState { CUR, NEXT, PAST, HIDDEN }

/** Pixels per 1920-grid unit, set once per frame by HomeScreen (graphicsLayer lambdas cannot read composition locals). */
object LocalScaleHolder {
    var px = 1f
}

@Composable
private fun RowView(
    row: Row, y: Float, state: RowState, rowsMode: Boolean, trailer: Boolean, focusedCol: Int, col: Int,
    nav: Nav, vm: HomeViewModel, accent: State<Color>,
) {
    val sc = animateFloatAsState(when (state) { RowState.NEXT -> 0.93f; RowState.PAST -> 1.04f; else -> 1f }, tween(500), label = "rs")
    val ty = animateFloatAsState(when (state) {
        RowState.NEXT -> -8f
        RowState.PAST -> -60f
        else -> if (trailer && rowsMode) 90f else 0f
    }, tween(500), label = "rty")
    val al = animateFloatAsState(when {
        trailer && !(rowsMode && state == RowState.CUR) -> 0f
        state == RowState.NEXT -> 0.55f
        state == RowState.PAST -> 0f
        !rowsMode -> 0.92f
        else -> 1f
    }, tween(380), label = "ra")
    val target = Geo.shift(row, col)
    val shift = animateFloatAsState(target, tween(340, easing = FastOutSlowInEasing), label = "shift")

    // Explicit height: the tiles sit below the title by offset, which does not count toward the size, and while the
    // row fades its layer is cut to that size.
    Box(
        Modifier.fillMaxWidth().offset(y = y.u).height(Geo.height(row).u).graphicsLayer {
            scaleX = sc.value; scaleY = sc.value; alpha = al.value
            translationY = ty.value * LocalScaleHolder.px
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(Geo.LEFT / 1920f, 0f)
        },
    ) {
        BasicText(row.title, modifier = Modifier.offset(x = Geo.LEFT.u), style = text(27, 600, Color(0xEBECEFF5)))
        val w = Geo.w(row.kind)
        val h = Geo.h(row.kind)
        if (row.kind == RowKind.APP_GRID) {
            Column(Modifier.offset(x = Geo.LEFT.u, y = Geo.TITLE.u), verticalArrangement = Arrangement.spacedBy(Geo.GRID_GAP_Y.u)) {
                row.tiles.chunked(Geo.GRID_COLS).forEachIndexed { li, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Geo.GAP.u)) {
                        line.forEachIndexed { ci, tile ->
                            val idx = li * Geo.GRID_COLS + ci
                            TileView(tile, row.kind, w, h, idx == focusedCol, nav, vm, accent)
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier.offset(y = Geo.TITLE.u).wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = (Geo.LEFT - shift.value) * LocalScaleHolder.px },
                horizontalArrangement = Arrangement.spacedBy(Geo.GAP.u),
            ) {
                // Tiles far off screen are skipped: a row can hold 20 posters, only ~9 are visible. The window spans both
                // where the scroll is now and where it is heading: on fast presses the animation lags several tiles
                // behind the target, and those tiles are still on screen. Recomposes only when a tile boundary is crossed.
                val step = w + Geo.GAP
                val now by remember(shift, step) { derivedStateOf { (shift.value / step).toInt() } }
                val goal = (target / step).toInt()
                val first = (minOf(now, goal) - 2).coerceAtLeast(0)
                if (first > 0) Spacer(Modifier.width((first * step - Geo.GAP).u))
                val last = (maxOf(now, goal) + 10).coerceAtMost(row.tiles.lastIndex)
                for (c in first..last) {
                    androidx.compose.runtime.key(row.tiles[c].key) {
                        TileView(row.tiles[c], row.kind, w, h, c == focusedCol, nav, vm, accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun TileView(
    tile: Tile, kind: RowKind, w: Float, h: Float, focused: Boolean,
    nav: Nav, vm: HomeViewModel, accent: State<Color>,
) {
    // Focus progress drives scale, shadow and glow inside one graphics layer: no recomposition while it animates.
    val f = animateFloatAsState(if (focused) 1f else 0f, spring(0.6f, Spring.StiffnessMediumLow), label = "tf")
    val shine = remember { Animatable(0f) }
    LaunchedEffect(focused) { if (focused) { shine.snapTo(0f); shine.animateTo(1f, tween(800)) } }
    val shape = RoundedCornerShape(18.u)

    Box(
        Modifier.size(w.u, h.u).zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                val p = f.value
                val s = 1f + 0.12f * p
                scaleX = s; scaleY = s
                rotationX = if (focused) nav.kickX.value else 0f
                rotationY = if (focused) nav.kickY.value else 0f
                cameraDistance = 14f * density
                shadowElevation = 18f * p * LocalScaleHolder.px
                val glow = androidx.compose.ui.graphics.lerp(Color.Black, accent.value, p)
                ambientShadowColor = glow
                spotShadowColor = glow
                this.shape = shape
                clip = true
            }
            .background(Color(0xFF15171C)),
    ) {
        when (tile) {
            is TitleTile -> PosterContent(tile, vm)
            is ContinueTile -> ContinueContent(tile, vm)
            is CollectionTile -> CollectionContent(tile)
            is GenreTile -> GenreContent(tile)
            is AppTile -> AppContent(tile)
            is MoreTile -> MoreContent()
        }
        if (focused) Box(Modifier.fillMaxSize().drawWithContent {
            drawContent()
            val p = shine.value
            if (p in 0.001f..0.999f) {
                val x = -0.6f * size.width + p * 1.9f * size.width
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x48FFFFFF), Color.Transparent),
                    Offset(x, 0f), Offset(x + size.width * 0.4f, size.height * 0.25f)), blendMode = BlendMode.Plus)
            }
        }.border(3.u, Color(0xE6FFFFFF), shape))
    }
}

@Composable
private fun BoxScope.PosterContent(t: TitleTile, vm: HomeViewModel) {
    AsyncImage(Tmdb.img(t.title.poster, "w342"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    vm.newEpisode(t.title, short = true)?.let { NewEpisodeBadge(it) }
}

/** The one text a poster carries: a followed series has a new episode out, or the date of the next one. */
@Composable
fun BoxScope.NewEpisodeBadge(label: String) {
    Box(
        Modifier.align(Alignment.TopStart).padding(10.u).clip(RoundedCornerShape(9.u)).background(Color(0xF2FFFFFF))
            .padding(horizontal = 10.u, vertical = 5.u),
    ) { BasicText(label, style = text(16, 700, Colors.FocusFg)) }
}

@Composable
private fun BoxScope.ContinueContent(t: ContinueTile, vm: HomeViewModel) {
    LaunchedEffect(t.key) { vm.requestDetails(t.title) }
    AsyncImage(Tmdb.img(vm.details[t.title.key]?.backdrop, "w780") ?: t.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0x99000000))))
    Box(Modifier.align(Alignment.BottomStart).padding(start = 22.u, end = 22.u, bottom = 34.u)) {
        LogoTitle(t.title.name, Tmdb.img(vm.details[t.title.key]?.logo, "w300"), 44f, maxW = 376, maxH = 70)
    }
    // A new episode of the series beats the "which episode / time left" label.
    val fresh = vm.newEpisode(t.title)
    val label = fresh ?: t.label
    if (label.isNotEmpty()) Box(
        Modifier.align(Alignment.TopEnd).padding(14.u).clip(RoundedCornerShape(9.u))
            .background(if (fresh != null) Color(0xF2FFFFFF) else Color(0xB30E0F13)).padding(horizontal = 10.u, vertical = 5.u),
    ) { BasicText(label, style = text(16, 700, if (fresh != null) Colors.FocusFg else Colors.Fg)) }
    if (t.progress > 0f) Box(
        Modifier.align(Alignment.BottomCenter).padding(start = 22.u, end = 22.u, bottom = 16.u).fillMaxWidth().height(6.u)
            .clip(RoundedCornerShape(3.u)).background(Color(0x40FFFFFF)),
    ) {
        Box(Modifier.fillMaxWidth(t.progress).height(6.u).clip(RoundedCornerShape(3.u)).background(Color.White))
    }
}

@Composable
private fun BoxScope.CollectionContent(t: CollectionTile) {
    AsyncImage(Tmdb.img(t.cover, "w780"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(hsl(t.hue, 0.85f, 0.45f, 0.88f), hsl(t.hue + 40f, 0.8f, 0.25f, 0.6f), Color(0x33000000)))))
    Column(Modifier.align(Alignment.BottomStart).padding(start = 26.u, end = 26.u, bottom = 22.u)) {
        Ico(when (t.icon) {
            CollIcon.MOON -> Icons.Moon
            CollIcon.CLOCK -> Icons.Clock
            CollIcon.DOC -> Icons.Doc
            CollIcon.TV -> Icons.Tv
            CollIcon.FAMILY -> Icons.Family
            CollIcon.STAR -> Icons.Star
        }, 42.u, Color.White)
        Spacer(Modifier.height(10.u))
        BasicText(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = text(29, 800))
        if (t.caption.isNotEmpty()) BasicText(t.caption, style = text(18, 400, Color(0xD9FFFFFF)))
    }
}

/**
 * A genre, kept low: its colour, the name on the left and, on the right, the poster of its most popular title tilted
 * and cut by the tile's edge. Nothing else; the hero tells the rest.
 */
@Composable
private fun BoxScope.GenreContent(t: GenreTile) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(hsl(t.hue, 0.7f, 0.38f), hsl(t.hue + 35f, 0.65f, 0.18f)))))
    val posterShape = RoundedCornerShape(10.u)
    AsyncImage(
        Tmdb.img(t.poster, "w185"), null,
        Modifier.align(Alignment.CenterEnd).offset(x = 14.u, y = 18.u).size(118.u, 177.u)
            .graphicsLayer { rotationZ = 10f; shadowElevation = 12f * LocalScaleHolder.px; shape = posterShape; clip = true },
        contentScale = ContentScale.Crop,
    )
    BasicText(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.u).width(190.u), style = text(27, 800))
}

@Composable
private fun BoxScope.AppContent(t: AppTile) {
    val banner = t.app.banner
    if (banner != null) {
        Image(banner, t.app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A3E47), Color(0xFF17191E)))))
        Row(Modifier.align(Alignment.Center).padding(horizontal = 18.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)) {
            t.app.icon?.let { Image(it, null, Modifier.size(64.u)) }
            BasicText(t.app.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = text(26, 700))
        }
    }
}

@Composable
private fun BoxScope.MoreContent() {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF)))))
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.u)) {
        Ico(Icons.Grid, 48.u, Color.White)
        BasicText("Смотреть\nвсе", style = text(23, 600).merge(androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center)))
    }
}
