package ru.homescreen.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import ru.homescreen.tv.PlayerControls
import ru.homescreen.tv.PlayerControls.Mode
import ru.homescreen.tv.Playback
import ru.homescreen.tv.data.PlayerPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EnColor = Color(0xFFF2D675)
private val Card = Color(0xF5141519)
private val Pale = Color(0x1FFFFFFF)
private val Sub = Color(0xFF4A4F58)

/**
 * The built-in player's screen over the video (see the mockup «Плеер ShowyLauncher»): the two subtitle lines, the panel
 * OK opens, the seek bar, the sound and subtitle menus, the episodes, and the intro and next-episode buttons. All state
 * is in [PlayerControls].
 */
@Composable
fun PlayerScreen(c: PlayerControls, view: PlayerView) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ view }, Modifier.fillMaxSize())
        Fade(c.mode == Mode.PANEL) { Panel(c) }
        Lines(c)
        Fade(c.mode == Mode.SEEK) { SeekOverlay(c) }
        Fade(c.mode == Mode.AUDIO) { AudioMenu(c) }
        Fade(c.mode == Mode.SUBS) { SubsMenu(c) }
        Fade(c.mode == Mode.EPISODES) { EpisodesOverlay(c) }
        // The last value stays for the fade out.
        val skip = remember { arrayOfNulls<PlayerControls.Skip>(1) }
        c.skip?.let { skip[0] = it }
        Fade(c.mode == Mode.NONE && c.skip != null && !c.nextShown) { skip[0]?.let { SkipPrompt(c, it) } }
        Fade(c.mode == Mode.NONE && c.nextShown) { NextCard(c) }
        Fade(c.loading) { Spinner() }
        val toast = remember { arrayOf("") }
        c.toast?.let { toast[0] = it }
        Fade(c.toast != null) { Toast(toast[0]) }
    }
}

@Composable
private fun Fade(visible: Boolean, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) { content() }

// ---------- Subtitles ----------

/** English on top in soft yellow, Russian under it; raised over the panel, moved left beside a menu. */
@Composable
private fun Lines(c: PlayerControls) {
    if (c.mode == Mode.SEEK || c.mode == Mode.EPISODES || (c.upper.isEmpty() && c.lower.isEmpty())) return
    val size = PlayerPrefs.SIZES[PlayerPrefs.subsSize].first
    val lift by animateFloatAsState(if (c.mode == Mode.PANEL) 246f else 0f, tween(220), label = "lift")
    val (left, width) = when (c.mode) {
        Mode.AUDIO -> 96 to 1088
        Mode.SUBS -> 96 to 688
        else -> 160 to 1600
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = left.u, bottom = 84.u).width(width.u)
                .graphicsLayer { translationY = -lift * LocalScaleHolder.px },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.u),
        ) {
            if (c.upper.isNotEmpty()) BasicText(c.upper, style = lineStyle(size, EnColor))
            if (c.lower.isNotEmpty()) BasicText(c.lower, style = lineStyle(size, Colors.Fg))
        }
    }
}

@Composable
private fun lineStyle(size: Int, color: Color) = text(size, 500, color).copy(
    textAlign = TextAlign.Center,
    lineHeight = (size * 1.24f).t,
    shadow = Shadow(Color(0xE6000000), Offset(0f, 2f * LocalScaleHolder.px), 8f * LocalScaleHolder.px),
)

// ---------- Panel ----------

@Composable
private fun Panel(c: PlayerControls) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(320.u).background(Brush.verticalGradient(listOf(Color(0xE0050608), Color(0x00050608)))))
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(560.u)
                .background(Brush.verticalGradient(0f to Color(0x00050608), 0.55f to Color(0xB3050608), 1f to Color(0xF0050608))),
        )
        Column(Modifier.padding(start = 96.u, top = 72.u).width(1200.u), verticalArrangement = Arrangement.spacedBy(10.u)) {
            BasicText(c.playback.title, style = text(64, 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.playback.serial) BasicText(episodeLine(c.item), style = text(30, 400, Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Clock(c, Modifier.align(Alignment.TopEnd).padding(top = 64.u, end = 96.u))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 96.u, end = 96.u, bottom = 72.u).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(40.u),
        ) {
            Bar(c, focused = c.onBar)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.u)) {
                c.buttons.forEachIndexed { i, b ->
                    val f = !c.onBar && c.button == i
                    when (b) {
                        PlayerControls.Button.PLAY -> RoundButton(if (c.playing) Icons.Pause else Icons.Play, f)
                        PlayerControls.Button.AUDIO -> Pill(Icons.Audio, audioLabel(c), f)
                        PlayerControls.Button.SUBS -> Pill(Icons.Subtitles, subsLabel(c), f)
                        PlayerControls.Button.EPISODES -> Pill(Icons.Episodes, "Серии", f)
                        PlayerControls.Button.NEXT -> Pill(Icons.Next, "Серия ${c.playback.items[c.index + 1].episode}", f)
                    }
                }
                Spacer(Modifier.weight(1f))
                c.badges.forEach { Badge(it) }
            }
        }
    }
}

private fun episodeLine(it: Playback.Item) = listOfNotNull("Сезон ${it.season}", "Серия ${it.episode}", it.name).joinToString(" · ")

@Composable
private fun Clock(c: PlayerControls, modifier: Modifier) {
    // Read so the clock follows the ticks.
    val left = (c.duration - c.position).coerceAtLeast(0)
    val now = System.currentTimeMillis()
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.u)) {
        BasicText(hhmm(now), style = text(72, 300))
        if (c.duration > 0) BasicText("Конец в ${hhmm(now + left)}", style = text(26, 400, Colors.Muted))
    }
}

private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

private fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun audioLabel(c: PlayerControls): String {
    val t = c.audio.getOrNull(c.audioSelected) ?: return "Звук"
    val ch = Regex("\\d\\.\\d|Стерео|Моно").find(t.sub)?.value
    return listOfNotNull(t.title, ch).joinToString(" ")
}

private fun subsLabel(c: PlayerControls): String {
    val langs = listOf(c.textUpper, c.textLower).filter { it != PlayerControls.OFF }
        .map { k -> k.substringBefore('|').take(2).uppercase().takeIf { it.isNotEmpty() && it != "NU" } ?: "?" }
    return if (langs.isEmpty()) "Выкл" else langs.joinToString(" + ")
}

@Composable
private fun RoundButton(icon: ImageVector, focused: Boolean) {
    val sc by animateFloatAsState(if (focused) 1.06f else 1f, label = "rb")
    Box(
        Modifier.size(88.u).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
            .then(if (focused) Modifier.background(Colors.Focus) else Modifier.background(Pale)),
        contentAlignment = Alignment.Center,
    ) { Ico(icon, 38.u, if (focused) Colors.FocusFg else Colors.Fg) }
}

@Composable
private fun Pill(icon: ImageVector?, label: String, focused: Boolean) {
    val sc by animateFloatAsState(if (focused) 1.05f else 1f, label = "pill")
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    Row(
        Modifier.height(72.u).graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(36.u))
            .then(if (focused) Modifier.background(Colors.Focus) else Modifier.background(Pale))
            .padding(start = if (icon != null) 26.u else 32.u, end = 32.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.u),
    ) {
        icon?.let { Ico(it, 32.u, fg) }
        BasicText(label, style = text(28, 500, fg))
    }
}

@Composable
private fun Badge(label: String) {
    BasicText(
        label,
        Modifier.border(2.u, Color(0x61ECEFF5), RoundedCornerShape(10.u)).padding(horizontal = 12.u, vertical = 6.u),
        style = text(22, 600, Color(0xC7ECEFF5)),
    )
}

// ---------- Seeking ----------

/** The progress bar; focused or seeking it grows a knob, and the target time rides above it. */
@Composable
private fun Bar(c: PlayerControls, focused: Boolean) {
    val d = c.duration.coerceAtLeast(1)
    val target = c.seekTarget
    val shown = target ?: c.position
    val big = focused || target != null
    val thick = if (big) 10.u else 8.u
    Box {
        Column(verticalArrangement = Arrangement.spacedBy(16.u)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(36.u)) {
                val w = maxWidth
                val frac = (shown.toFloat() / d).coerceIn(0f, 1f)
                val shape = RoundedCornerShape(5.u)
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(thick).clip(shape).background(Color(0x38FFFFFF)))
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth((c.buffered.toFloat() / d).coerceIn(0f, 1f)).height(thick).clip(shape).background(Color(0x61FFFFFF)))
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(thick).clip(shape).background(Colors.Fg))
                if (target != null) {
                    val from = (c.seekFrom.toFloat() / d).coerceIn(0f, 1f)
                    Box(Modifier.align(Alignment.CenterStart).offset(x = w * from - 2.u).size(4.u, 30.u).clip(RoundedCornerShape(2.u)).background(Color(0xB3ECEFF5)))
                }
                if (big) Box(
                    Modifier.align(Alignment.CenterStart).offset(x = w * frac - 18.u).size(36.u)
                        .shadow(12.u, CircleShape).clip(CircleShape).background(Color.White),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                BasicText(time(shown), style = text(28, 500))
                Spacer(Modifier.weight(1f))
                BasicText("−" + time(d - shown), style = text(28, 400, Colors.Muted))
            }
        }
        if (target != null) Bubble(c, target, d)
    }
}

/** The time ← → have reached and how far from where the seek began. */
@Composable
private fun Bubble(c: PlayerControls, target: Long, d: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(0.u)) {
        val w = maxWidth
        val x = (w * (target.toFloat() / d) - 120.u).coerceIn(0.u, w - 240.u)
        val delta = target - c.seekFrom
        Column(
            // The bar's slot has no height for it: measured on its own, drawn above the knob.
            Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(x = x, y = (-128).u).width(240.u).shadow(24.u, RoundedCornerShape(20.u)).clip(RoundedCornerShape(20.u))
                .background(Colors.Focus).padding(top = 14.u, bottom = 12.u),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(time(target), style = text(48, 600, Colors.FocusFg))
            BasicText((if (delta >= 0) "+" else "−") + time(kotlin.math.abs(delta)), style = text(24, 500, Sub))
        }
    }
}

@Composable
private fun SeekOverlay(c: PlayerControls) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(380.u)
                .background(Brush.verticalGradient(0f to Color(0x00050608), 0.5f to Color(0x99050608), 1f to Color(0xEB050608))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 96.u, end = 96.u, bottom = 96.u).fillMaxWidth()) { Bar(c, focused = true) }
    }
}

// ---------- Menus ----------

@Composable
private fun MenuShade() {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.CenterEnd).width(1300.u).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color(0x00050608), Color(0xCC050608)))))
    }
}

@Composable
private fun MenuCard(width: Int, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 72.u, bottom = 72.u).width(width.u)
                .shadow(40.u, RoundedCornerShape(32.u)).clip(RoundedCornerShape(32.u)).background(Card).padding(16.u),
            verticalArrangement = Arrangement.spacedBy(6.u),
        ) { content() }
    }
}

@Composable
private fun MenuTitle(label: String) {
    BasicText(label, Modifier.padding(start = 24.u, top = 20.u, bottom = 10.u), style = text(26, 500, Colors.Muted))
}

@Composable
private fun Choice(title: String, sub: String?, focused: Boolean, checked: Boolean, modifier: Modifier = Modifier) {
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    val sc by animateFloatAsState(if (focused) 1.02f else 1f, label = "ch")
    Row(
        modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(20.u))
            .then(if (focused) Modifier.background(Colors.Focus) else Modifier)
            .padding(horizontal = 22.u, vertical = 16.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.u),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = text(28, if (focused) 600 else 500, fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) BasicText(sub, style = text(21, 400, if (focused) Sub else Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) Ico(Icons.Check, 28.u, fg)
    }
}

@Composable
private fun AudioMenu(c: PlayerControls) {
    MenuShade()
    MenuCard(600) {
        MenuTitle("Звук")
        c.audio.forEachIndexed { i, t -> Choice(t.title, t.sub.ifEmpty { null }, i == c.audioIdx, i == c.audioSelected) }
    }
}

/** Two columns of the same tracks, the upper line's and the lower one's; six rows at a time around the focus. */
@Composable
private fun SubsMenu(c: PlayerControls) {
    MenuShade()
    MenuCard(1000) {
        MenuTitle("Субтитры")
        val rows = c.texts.size + 1
        val first = (c.subsRow - 2).coerceIn(0, (rows - ROWS).coerceAtLeast(0))
        Row(horizontalArrangement = Arrangement.spacedBy(16.u)) {
            for (col in 0..1) {
                val chosen = if (col == 0) c.textUpper else c.textLower
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.u)) {
                    Row(Modifier.padding(start = 22.u, top = 8.u, bottom = 10.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.u)) {
                        Box(Modifier.size(14.u).clip(CircleShape).background(if (col == 0) EnColor else Colors.Fg))
                        BasicText(if (col == 0) "Верхняя строка" else "Нижняя строка", style = text(24, 600))
                    }
                    for (r in first until (first + ROWS).coerceAtMost(rows)) {
                        val focused = !c.onSize && c.subsCol == col && c.subsRow == r
                        val t = c.texts.getOrNull(r)
                        if (t != null) Choice(t.title, t.sub, focused, t.key == chosen)
                        else Choice("Выключить", null, focused, chosen == PlayerControls.OFF)
                    }
                }
            }
        }
        if (c.texts.isEmpty()) BasicText("В файле нет текстовых субтитров", Modifier.padding(start = 22.u, top = 4.u), style = text(22, 400, Colors.Muted))
        Box(Modifier.padding(horizontal = 22.u, vertical = 8.u).fillMaxWidth().height(2.u).background(Color(0x14FFFFFF)))
        val size = PlayerPrefs.SIZES[PlayerPrefs.subsSize].second
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.u)).then(if (c.onSize) Modifier.background(Colors.Focus) else Modifier)
                .padding(horizontal = 22.u, vertical = 10.u),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.u),
        ) {
            val fg = if (c.onSize) Colors.FocusFg else Colors.Fg
            BasicText("Размер", Modifier.weight(1f), style = text(28, 500, fg))
            Ico(Icons.Left, 28.u, fg)
            BasicText(size, Modifier.width(170.u), style = text(28, 500, fg).copy(textAlign = TextAlign.Center))
            Ico(Icons.Right, 28.u, fg)
        }
    }
}

private const val ROWS = 6

// ---------- Episodes ----------

@Composable
private fun EpisodesOverlay(c: PlayerControls) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(760.u)
                .background(Brush.verticalGradient(0f to Color(0x00050608), 0.45f to Color(0xD9050608), 1f to Color(0xF5050608))),
        )
        BasicText(c.playback.title, Modifier.padding(start = 96.u, top = 72.u), style = text(64, 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(start = 96.u, top = 470.u), horizontalArrangement = Arrangement.spacedBy(48.u)) {
            c.seasons.forEachIndexed { i, s ->
                val sel = i == c.epSeason
                val focused = sel && c.epOnTabs
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.u)) {
                    BasicText(
                        "Сезон $s",
                        Modifier.clip(RoundedCornerShape(24.u)).then(if (focused) Modifier.background(Colors.Focus) else Modifier)
                            .padding(horizontal = if (focused) 20.u else 0.u, vertical = if (focused) 6.u else 0.u),
                        style = text(30, if (sel) 600 else 500, if (focused) Colors.FocusFg else if (sel) Colors.Fg else Colors.Muted),
                    )
                    if (sel && !focused) Box(Modifier.size(22.u, 4.u).clip(RoundedCornerShape(2.u)).background(Colors.Fg))
                }
            }
        }
        val list = c.seasonItems()
        val scroll by animateFloatAsState(((c.epIdx - 2).coerceAtLeast(0) * 432).toFloat(), tween(260), label = "eps")
        Row(
            Modifier.padding(start = 96.u, top = 560.u).wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = -scroll * LocalScaleHolder.px },
            horizontalArrangement = Arrangement.spacedBy(32.u),
        ) {
            list.forEachIndexed { k, (i, item) ->
                EpisodeCard(item, now = i == c.index, focused = !c.epOnTabs && k == c.epIdx, progress = c.playback.progress(i))
            }
        }
    }
}

@Composable
private fun EpisodeCard(item: Playback.Item, now: Boolean, focused: Boolean, progress: Float) {
    val sc by animateFloatAsState(if (focused) 1.05f else 1f, label = "ep")
    Column(Modifier.width(400.u), verticalArrangement = Arrangement.spacedBy(14.u)) {
        Box(
            Modifier.size(400.u, 225.u).graphicsLayer { scaleX = sc; scaleY = sc }
                .then(if (focused) Modifier.border(5.u, Colors.Fg, RoundedCornerShape(18.u)) else Modifier)
                .clip(RoundedCornerShape(18.u)).background(Color(0xFF1A1C21)),
        ) {
            if (item.still != null) AsyncImage(item.still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else BasicText("${item.episode}", Modifier.align(Alignment.Center), style = text(72, 300, Colors.Faint))
            if (now) BasicText(
                "Сейчас",
                Modifier.padding(14.u).clip(RoundedCornerShape(8.u)).background(Color(0xF0F4F5F7)).padding(horizontal = 12.u, vertical = 4.u),
                style = text(20, 600, Colors.FocusFg),
            )
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).padding(14.u).fillMaxWidth().height(6.u).clip(RoundedCornerShape(3.u)).background(Color(0x4DFFFFFF))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.u).background(Colors.Fg))
            }
        }
        BasicText("Серия ${item.episode}", style = text(26, if (focused) 600 else 500))
        val sub = item.name ?: item.runtimeMin.takeIf { it > 0 }?.let { "$it мин" }
        if (sub != null) BasicText(sub, Modifier.offset(y = (-8).u), style = text(22, 400, Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Prompts ----------

/** «Пропустить заставку» filling up as the intro plays, or after a skip by itself «Вернуться». */
@Composable
private fun SkipPrompt(c: PlayerControls, s: PlayerControls.Skip) {
    val intro = s.kind == PlayerControls.Kind.INTRO
    Box(Modifier.fillMaxSize()) {
        // Centred on the corner: the box must reach as far as the radius, or its edge shows.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xB3050608), Color(0x00050608)), center = Offset.Infinite, radius = 900f * LocalScaleHolder.px)))
        val mod = Modifier.align(Alignment.BottomEnd).padding(end = 96.u, bottom = 120.u)
        if (s.auto) Row(
            mod.shadow(24.u, RoundedCornerShape(50.u)).clip(RoundedCornerShape(50.u)).background(Card).padding(start = 34.u, top = 10.u, end = 10.u, bottom = 10.u),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.u),
        ) {
            BasicText(if (intro) "Заставка пропущена" else "Пересказ пропущен", style = text(28, 500))
            FillButton(Icons.Undo, "Вернуться", 1f, focused = true)
        }
        else {
            val span = (s.range.last - s.range.first).coerceAtLeast(1)
            val fill = ((c.position - s.range.first).toFloat() / span).coerceIn(0f, 1f)
            Box(mod.shadow(24.u, RoundedCornerShape(40.u))) {
                FillButton(Icons.SkipAhead, if (intro) "Пропустить заставку" else "Пропустить пересказ", fill, focused = true)
            }
        }
    }
}

/** A pill whose lighter part grows with [fill]: how far the intro or the countdown has got. */
@Composable
private fun FillButton(icon: ImageVector?, label: String, fill: Float, focused: Boolean) {
    val f by animateFloatAsState(fill, tween(260, easing = LinearEasing), label = "fill")
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    Box(Modifier.height(76.u).clip(RoundedCornerShape(38.u)).background(if (focused) Color(0xFFDADDE3) else Pale)) {
        Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(if (focused) Color.White else Color(0x24FFFFFF)))
        }
        Row(
            Modifier.padding(start = if (icon != null) 30.u else 36.u, end = 36.u).height(76.u),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.u),
        ) {
            icon?.let { Ico(it, 30.u, fg) }
            BasicText(label, style = text(30, 600, fg))
        }
    }
}

@Composable
private fun NextCard(c: PlayerControls) {
    val next = c.playback.items.getOrNull(c.index + 1) ?: return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xCC050608), Color(0x00050608)), center = Offset.Infinite, radius = 1100f * LocalScaleHolder.px)))
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 96.u, bottom = 96.u).width(780.u)
                .shadow(40.u, RoundedCornerShape(32.u)).clip(RoundedCornerShape(32.u)).background(Card).padding(28.u),
            verticalArrangement = Arrangement.spacedBy(28.u),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.u)) {
                Box(Modifier.size(272.u, 153.u).clip(RoundedCornerShape(16.u)).background(Color(0xFF1A1C21))) {
                    if (next.still != null) AsyncImage(next.still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else BasicText("${next.episode}", Modifier.align(Alignment.Center), style = text(56, 300, Colors.Faint))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.u)) {
                    BasicText("Следующая серия", style = text(24, 500, Colors.Muted))
                    BasicText("Серия ${next.episode}", style = text(40, 600))
                    val meta = listOfNotNull(next.name, next.runtimeMin.takeIf { it > 0 }?.let { "$it мин" }).joinToString(" · ")
                    if (meta.isNotEmpty()) BasicText(meta, style = text(24, 400, Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.u)) {
                FillButton(Icons.Play, "Смотреть через ${c.nextLeft}", c.nextFill, focused = c.nextFocus == 0)
                Pill(null, "Досмотреть титры", c.nextFocus == 1)
            }
        }
    }
}

// ---------- Small things ----------

@Composable
private fun Spinner() {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val width = 6.u
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(72.u).graphicsLayer { rotationZ = a }) {
            drawArc(Color(0xE6F4F5F7), 0f, 270f, false, style = Stroke(width = width.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Toast(label: String) {
    Box(Modifier.fillMaxSize()) {
        BasicText(
            label,
            Modifier.align(Alignment.TopCenter).padding(top = 72.u).shadow(24.u, RoundedCornerShape(30.u)).clip(RoundedCornerShape(30.u))
                .background(Card).padding(horizontal = 32.u, vertical = 16.u),
            style = text(28, 500),
        )
    }
}
