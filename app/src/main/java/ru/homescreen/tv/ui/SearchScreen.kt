package ru.homescreen.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import ru.homescreen.tv.SearchSession
import ru.homescreen.tv.SearchSession.Act
import ru.homescreen.tv.SearchSession.Zone
import ru.homescreen.tv.VoiceInput
import ru.homescreen.tv.data.Media
import ru.homescreen.tv.data.Tmdb

private const val KEY_W = 84f
private const val KEY_H = 64f
private const val KEY_GAP = 10f
private const val POSTER_W = 188f
private const val POSTER_H = 282f
private const val POSTER_GAP = 24f
private const val RESULTS_X = 760f
private const val GRID_Y = 250f
private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val DOTS = listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC05), Color(0xFF34A853))

/** The keyboard on the left, posters on the right; the focused result's name sits above the grid. */
@Composable
fun SearchScreen(s: SearchSession?, voice: VoiceInput, visible: Boolean) {
    AnimatedVisibility(visible && s != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
        val w = s ?: return@AnimatedVisibility
        val focused = if (w.zone == Zone.RESULTS) w.results.getOrNull(w.idx) else null
        Box(Modifier.fillMaxSize().background(Colors.Bg)) {
            focused?.backdrop?.let {
                AsyncImage(Tmdb.img(it, "w780"), null, Modifier.fillMaxSize(), alpha = 0.3f, contentScale = ContentScale.Crop)
                BakedGradient(listOf(0f to Color(0xFA050608), 0.6f to Color(0xD9050608), 1f to Color(0x99050608)), Modifier.fillMaxSize())
            }

            // What is typed, with a caret; a hint while empty. Listening: the words so far, then the jumping dots.
            Row(Modifier.offset(x = 96.u, y = 92.u).widthIn(max = 620.u), verticalAlignment = Alignment.CenterVertically) {
                if (w.query.isNotEmpty()) BasicText(w.query, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.u), style = text(Type.TITLE, 700))
                else if (!voice.listening) BasicText("Фильм или сериал", style = text(Type.TITLE, 500, Colors.Faint))
                if (voice.listening) {
                    if (w.query.isNotEmpty()) Spacer(Modifier.width(20.u))
                    VoiceDots(voice)
                } else {
                    Box(Modifier.width(3.u).height(44.u).background(if (w.zone == Zone.KEYS) Color.White else Colors.Faint))
                }
            }

            Keyboard(w)
            Results(w)
        }
    }
}

/** Google TV's listening sign: four dots that jump higher the louder the voice. Composed only while listening. */
@Composable
private fun VoiceDots(v: VoiceInput) {
    val phase = rememberInfiniteTransition(label = "dots").animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "dp")
    Row(Modifier.height(44.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)) {
        DOTS.forEachIndexed { i, c ->
            Box(
                Modifier.size(20.u).graphicsLayer {
                    // Read here, not in composition: the dots move without recomposing the screen.
                    val wave = kotlin.math.abs(kotlin.math.sin((phase.value + i * 0.18f) * 2f * Math.PI.toFloat()))
                    translationY = -wave * (5f + v.level * 22f) * LocalScaleHolder.px
                }.clip(CircleShape).background(c),
            )
        }
    }
}

@Composable
private fun Keyboard(w: SearchSession) {
    val focus = if (w.zone == Zone.KEYS) w.keyAt() else null
    Column(Modifier.offset(x = 96.u, y = 190.u), verticalArrangement = Arrangement.spacedBy(KEY_GAP.u)) {
        w.rows.forEachIndexed { r, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP.u)) {
                line.forEachIndexed { i, k ->
                    val f = focus != null && r == w.row && focus.first == i
                    val fg = if (f) Colors.FocusFg else Colors.Fg
                    val sc by animateFloatAsState(if (f) FocusSpec.CONTROL else 1f, label = "key")
                    Box(
                        Modifier.size((KEY_W * k.span + KEY_GAP * (k.span - 1)).u, KEY_H.u).graphicsLayer { scaleX = sc; scaleY = sc }.clip(Shapes.card)
                            .background(if (f) Colors.Focus else if (k.act == Act.CHAR) Colors.Pale else Colors.PaleStrong),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k.act) {
                            Act.VOICE -> Ico(Icons.Mic, 30.u, fg)
                            Act.CHAR -> BasicText(k.label, style = text(Type.LARGE, 500, fg))
                            else -> BasicText(k.label, style = text(Type.CAPTION, 600, fg))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Results(w: SearchSession) {
    val cols = SearchSession.RESULT_COLS
    val list = w.results
    val inGrid = w.zone == Zone.RESULTS
    val focused = if (inGrid) list.getOrNull(w.idx) else null

    // Header: the focused title, otherwise what the grid is.
    Column(Modifier.offset(x = RESULTS_X.u, y = 160.u).widthIn(max = 1060.u)) {
        if (focused != null) {
            BasicText(focused.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = text(Type.LARGE, 700))
            // The same facts line as the hero: the star is the icon, the facts are spaced, not dotted.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)) {
                if (focused.vote > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
                    Ico(Icons.Star, 24.u, Colors.Gold)
                    BasicText("%.1f".format(focused.vote), style = text(Type.SMALL, 700))
                }
                focused.year.takeIf { it.isNotEmpty() }?.let { BasicText(it, style = text(Type.SMALL, 400, Colors.Muted)) }
                BasicText(if (focused.media == Media.TV) "Сериал" else "Фильм", style = text(Type.SMALL, 400, Colors.Muted))
                if (w.sources[focused.key] == false) BasicText("Нет в источниках", style = text(Type.SMALL, 400, Colors.Muted))
            }
        } else {
            val label = when {
                w.loading -> "Ищем…"
                w.failed -> "Поиск не ответил, пробуем ещё…"
                w.searched && list.isEmpty() -> "Ничего не нашли"
                w.searched -> "Найдено"
                else -> "Популярное сейчас"
            }
            BasicText(label, style = text(Type.LARGE, 600, Colors.Muted))
        }
    }

    val step = POSTER_H + 30f
    val focusRow = if (inGrid) w.idx / cols else 0
    val y by animateFloatAsState(-((focusRow - 1).coerceAtLeast(0)) * step, tween(400, easing = FastOutSlowInEasing), label = "sy")
    Box(Modifier.offset(x = RESULTS_X.u, y = GRID_Y.u).graphicsLayer { translationY = y * LocalScaleHolder.px }) {
        val firstRow = (focusRow - 2).coerceAtLeast(0)
        list.chunked(cols).forEachIndexed { r, line ->
            if (r in firstRow..focusRow + 3) Row(Modifier.offset(y = (r * step).u), horizontalArrangement = Arrangement.spacedBy(POSTER_GAP.u)) {
                line.forEachIndexed { c, t ->
                    val f = inGrid && r * cols + c == w.idx
                    val p = animateFloatAsState(if (f) 1f else 0f, tween(200), label = "ss")
                    val shape = Shapes.card
                    val px = LocalScaleHolder.px
                    // No sources in Showy: grey and dim, still openable.
                    val none = w.sources[t.key] == false
                    Box(
                        Modifier.size(POSTER_W.u, POSTER_H.u).graphicsLayer {
                            val k = 1f + (FocusSpec.TILE - 1f) * p.value
                            scaleX = k; scaleY = k
                            shadowElevation = 30f * p.value * px
                            this.shape = shape; clip = true
                        }.background(Colors.Placeholder),
                    ) {
                        AsyncImage(Tmdb.img(t.poster, "w342"), null, Modifier.fillMaxSize(), alpha = if (none) 0.35f else 1f,
                            contentScale = ContentScale.Crop, colorFilter = if (none) GREY else null)
                        if (f) Box(Modifier.fillMaxSize().border(FocusSpec.RING.u, Colors.FocusRing, shape))
                    }
                }
            }
        }
    }
}
