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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
        // Scrims baked into strips: full-screen gradient shaders cost this GPU every frame (see Backdrop).
        BakedGradient(listOf(0f to Color(0xE0050608), 1f to Color(0x00050608)), Modifier.fillMaxWidth().height(320.u), vertical = true)
        BakedGradient(listOf(0f to Color(0x00050608), 0.55f to Color(0xB3050608), 1f to Color(0xF0050608)),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(560.u), vertical = true)
        Column(Modifier.padding(start = 96.u, top = 72.u).width(1200.u), verticalArrangement = Arrangement.spacedBy(10.u)) {
            BasicText(c.playback.title, style = text(Type.DISPLAY, 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.playback.serial) BasicText(episodeLine(c.item), style = text(Type.LARGE, 400, Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Clock(c, Modifier.align(Alignment.TopEnd).padding(top = 64.u, end = 96.u))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 96.u, end = 96.u, bottom = 96.u).fillMaxWidth(),
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

private fun episodeLine(it: Playback.Item) = listOfNotNull("Сезон ${it.season}", "Серия ${it.episode}", it.name).joinToString(", ")

@Composable
private fun Clock(c: PlayerControls, modifier: Modifier) {
    // Read so the clock follows the ticks.
    val left = (c.duration - c.position).coerceAtLeast(0)
    val now = System.currentTimeMillis()
    // The same zone as the home screen's clock (where the network puts the TV), so the two never differ by an hour.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hhmm = remember {
        val place = ru.homescreen.tv.data.WeatherApi.decode(
            ctx.getSharedPreferences("homescreen", android.content.Context.MODE_PRIVATE).getString("place", null))
        SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = place?.zoneId?.let { java.util.TimeZone.getTimeZone(it) } ?: java.util.TimeZone.getDefault()
        }
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.u)) {
        BasicText(hhmm.format(Date(now)), style = text(Type.NUMERAL, 300))
        if (c.duration > 0) BasicText("Конец в ${hhmm.format(Date(now + left))}", style = text(Type.BODY, 400, Colors.Muted))
    }
}


/** "32 Мбит/с"; below 10, with a decimal: "4,5 Мбит/с". */
private fun mbits(bps: Long): String {
    val m = bps / 1_000_000.0
    return if (m < 10) String.format(Locale("ru"), "%.1f Мбит/с", m) else "${m.toInt()} Мбит/с"
}

/** What is on disk ahead of the player, in words: "Запас 42 мин", "Запас 1 ч 05 мин", or "Загружено до конца". */
internal fun reserve(loadedTo: Long, position: Long, duration: Long): String {
    if (loadedTo >= duration) return "Загружено до конца"
    val m = ((loadedTo - position).coerceAtLeast(0) / 60_000).toInt()
    return when {
        m < 1 -> "Запас меньше минуты"
        m < 60 -> "Запас $m мин"
        else -> "Запас ${m / 60} ч %02d мин".format(m % 60)
    }
}

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
    val sc by animateFloatAsState(if (focused) FocusSpec.CONTROL else 1f, label = "rb")
    Box(
        Modifier.size(88.u).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
            .then(if (focused) Modifier.background(Colors.Focus) else Modifier.background(Colors.Pale)),
        contentAlignment = Alignment.Center,
    ) { Ico(icon, 38.u, if (focused) Colors.FocusFg else Colors.Fg) }
}

@Composable
private fun Pill(icon: ImageVector?, label: String, focused: Boolean) {
    val sc by animateFloatAsState(if (focused) FocusSpec.CONTROL else 1f, label = "pill")
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    Row(
        Modifier.height(72.u).graphicsLayer { scaleX = sc; scaleY = sc }.clip(Shapes.pill)
            .then(if (focused) Modifier.background(Colors.Focus) else Modifier.background(Colors.Pale))
            .padding(start = if (icon != null) 26.u else 32.u, end = 32.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.u),
    ) {
        icon?.let { Ico(it, 32.u, fg) }
        BasicText(label, style = text(Type.LARGE, 500, fg))
    }
}

@Composable
private fun Badge(label: String) {
    BasicText(
        label,
        Modifier.background(Colors.Pale, Shapes.small).padding(horizontal = 12.u, vertical = 6.u),
        style = text(Type.CAPTION, 600, Colors.Body),
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
                val shape = Shapes.pill
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(thick).clip(shape).background(Color(0x38FFFFFF)))
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth((c.buffered.toFloat() / d).coerceIn(0f, 1f)).height(thick).clip(shape).background(Color(0x61FFFFFF)))
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(thick).clip(shape).background(Colors.Fg))
                if (target != null) {
                    val from = (c.seekFrom.toFloat() / d).coerceIn(0f, 1f)
                    Box(Modifier.align(Alignment.CenterStart).offset(x = w * from - 2.u).size(4.u, 30.u).clip(Shapes.pill).background(Color(0xB3ECEFF5)))
                }
                if (big) Box(
                    Modifier.align(Alignment.CenterStart).offset(x = w * frac - 18.u).size(36.u)
                        .shadow(12.u, CircleShape).clip(CircleShape).background(Color.White),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                BasicText(time(shown), style = text(Type.LARGE, 500))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.u)) {
                    BasicText("−" + time(d - shown), style = text(Type.LARGE, 400, Colors.Muted))
                    c.loadedTo?.let { BasicText(reserve(it, c.position, d), style = text(Type.CAPTION, 400, Colors.Muted)) }
                    if (c.loadedTo != null && c.speed > 0) BasicText(mbits(c.speed), style = text(Type.CAPTION, 400, Colors.Muted))
                }
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
            Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(x = x, y = (-128).u).width(240.u).shadow(24.u, Shapes.card).clip(Shapes.card)
                .background(Colors.Focus).padding(top = 14.u, bottom = 12.u),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(time(target), style = text(Type.HEADING, 600, Colors.FocusFg))
            BasicText((if (delta >= 0) "+" else "−") + time(kotlin.math.abs(delta)), style = text(Type.SMALL, 500, Colors.OnFocusMuted))
        }
    }
}

@Composable
private fun SeekOverlay(c: PlayerControls) {
    Box(Modifier.fillMaxSize()) {
        BakedGradient(listOf(0f to Color(0x00050608), 0.5f to Color(0x99050608), 1f to Color(0xEB050608)),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(380.u), vertical = true)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 96.u, end = 96.u, bottom = 96.u).fillMaxWidth()) { Bar(c, focused = true) }
    }
}

// ---------- Menus ----------

@Composable
private fun MenuShade() {
    Box(Modifier.fillMaxSize()) {
        BakedGradient(listOf(0f to Color(0x00050608), 1f to Color(0xCC050608)), Modifier.align(Alignment.CenterEnd).width(1300.u).fillMaxHeight())
    }
}

@Composable
private fun MenuCard(width: Int, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            // The same surface, corners and padding as the menus on Home; on the same 96 margins as the panel.
            Modifier.align(Alignment.BottomEnd).padding(end = 96.u, bottom = 96.u).width(width.u)
                .shadow(20.u, Shapes.menu).clip(Shapes.menu).background(Colors.Surface).padding(14.u),
            verticalArrangement = Arrangement.spacedBy(6.u),
        ) { content() }
    }
}

@Composable
private fun MenuTitle(label: String) {
    BasicText(label, Modifier.padding(start = 24.u, top = 20.u, bottom = 10.u), style = text(Type.BODY, 500, Colors.Muted))
}

@Composable
private fun Choice(title: String, sub: String?, focused: Boolean, checked: Boolean, modifier: Modifier = Modifier) {
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    // A row only turns white (no scale), as the menus on Home do.
    Row(
        modifier.fillMaxWidth().clip(Shapes.card)
            .then(if (focused) Modifier.background(Colors.Focus) else Modifier)
            .padding(horizontal = 20.u, vertical = 16.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.u),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = text(Type.LARGE, if (focused) 600 else 500, fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) BasicText(sub, style = text(Type.CAPTION, 400, if (focused) Colors.OnFocusMuted else Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) Ico(Icons.Check, 28.u, fg)
    }
}

/** [AUDIO_ROWS] voices at a time around the focus: a remux can carry a dozen. */
@Composable
private fun AudioMenu(c: PlayerControls) {
    MenuShade()
    MenuCard(600) {
        MenuTitle("Звук")
        val first = (c.audioIdx - AUDIO_ROWS / 2).coerceIn(0, (c.audio.size - AUDIO_ROWS).coerceAtLeast(0))
        for (i in first until (first + AUDIO_ROWS).coerceAtMost(c.audio.size)) {
            val t = c.audio[i]
            Choice(t.title, t.sub.ifEmpty { null }, i == c.audioIdx, i == c.audioSelected)
        }
    }
}

private const val AUDIO_ROWS = 7

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
                        BasicText(if (col == 0) "Верхняя строка" else "Нижняя строка", style = text(Type.SMALL, 600))
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
        if (c.texts.isEmpty()) BasicText("В файле нет текстовых субтитров", Modifier.padding(start = 22.u, top = 4.u), style = text(Type.CAPTION, 400, Colors.Muted))
        Box(Modifier.padding(horizontal = 22.u, vertical = 8.u).fillMaxWidth().height(2.u).background(Colors.Pale))
        val size = PlayerPrefs.SIZES[PlayerPrefs.subsSize].second
        Row(
            Modifier.fillMaxWidth().clip(Shapes.card).then(if (c.onSize) Modifier.background(Colors.Focus) else Modifier)
                .padding(horizontal = 22.u, vertical = 10.u),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.u),
        ) {
            val fg = if (c.onSize) Colors.FocusFg else Colors.Fg
            BasicText("Размер", Modifier.weight(1f), style = text(Type.LARGE, 500, fg))
            Ico(Icons.Left, 28.u, fg)
            BasicText(size, Modifier.width(170.u), style = text(Type.LARGE, 500, fg).copy(textAlign = TextAlign.Center))
            Ico(Icons.Right, 28.u, fg)
        }
    }
}

private const val ROWS = 6

// ---------- Episodes ----------

@Composable
private fun EpisodesOverlay(c: PlayerControls) {
    Box(Modifier.fillMaxSize()) {
        BakedGradient(listOf(0f to Color(0x00050608), 0.45f to Color(0xD9050608), 1f to Color(0xF5050608)),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(760.u), vertical = true)
        BasicText(c.playback.title, Modifier.padding(start = 96.u, top = 72.u), style = text(Type.DISPLAY, 700), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The tabs' text sits on the margin; the focused pill's padding reaches into it.
        Row(Modifier.padding(start = 76.u, top = 470.u), horizontalArrangement = Arrangement.spacedBy(8.u)) {
            c.seasons.forEachIndexed { i, s ->
                val sel = i == c.epSeason
                val focused = sel && c.epOnTabs
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.u)) {
                    BasicText(
                        "Сезон $s",
                        // Constant padding: only the fill changes, so the tabs beside it do not jump.
                        Modifier.clip(Shapes.pill).then(if (focused) Modifier.background(Colors.Focus) else Modifier)
                            .padding(horizontal = 20.u, vertical = 6.u),
                        style = text(Type.LARGE, if (sel) 600 else 500, if (focused) Colors.FocusFg else if (sel) Colors.Fg else Colors.Muted),
                    )
                    if (sel && !focused) Box(Modifier.size(22.u, 4.u).clip(Shapes.pill).background(Colors.Fg))
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
            // Only the cards near the focus are composed: a season can hold two dozen stills.
            val first = (c.epIdx - 5).coerceAtLeast(0)
            val last = (c.epIdx + 6).coerceAtMost(list.lastIndex)
            if (first > 0) Spacer(Modifier.width((first * 432 - 32).u))
            for (k in first..last) {
                val (i, item) = list[k]
                androidx.compose.runtime.key(i) {
                    EpisodeCard(item, now = i == c.index, focused = !c.epOnTabs && k == c.epIdx, progress = c.playback.progress(i))
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(item: Playback.Item, now: Boolean, focused: Boolean, progress: Float) {
    val sc by animateFloatAsState(if (focused) FocusSpec.TILE else 1f, label = "ep")
    Column(Modifier.width(400.u), verticalArrangement = Arrangement.spacedBy(14.u)) {
        Box(
            Modifier.size(400.u, 225.u).graphicsLayer { scaleX = sc; scaleY = sc }
                .then(if (focused) Modifier.border(FocusSpec.RING.u, Colors.FocusRing, Shapes.card) else Modifier)
                .clip(Shapes.card).background(Colors.Placeholder),
        ) {
            if (item.still != null) AsyncImage(item.still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else BasicText("${item.episode}", Modifier.align(Alignment.Center), style = text(Type.NUMERAL, 300, Colors.Faint))
            if (now) BasicText(
                "Сейчас",
                Modifier.padding(14.u).clip(Shapes.small).background(Color(0xF0F4F5F7)).padding(horizontal = 12.u, vertical = 4.u),
                style = text(Type.CAPTION, 600, Colors.FocusFg),
            )
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).padding(14.u).fillMaxWidth().height(6.u).clip(Shapes.pill).background(Color(0x4DFFFFFF))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.u).background(Colors.Fg))
            }
        }
        BasicText("Серия ${item.episode}", style = text(Type.BODY, if (focused) 600 else 500))
        val sub = item.name ?: item.runtimeMin.takeIf { it > 0 }?.let { "$it мин" }
        if (sub != null) BasicText(sub, Modifier.offset(y = (-8).u), style = text(Type.CAPTION, 400, Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- Prompts ----------

/** «Пропустить заставку» filling up as the intro plays, or after a skip by itself «Вернуться». */
@Composable
private fun SkipPrompt(c: PlayerControls, s: PlayerControls.Skip) {
    val intro = s.kind == PlayerControls.Kind.INTRO
    Box(Modifier.fillMaxSize()) {
        // Centred on the corner: the box must reach as far as the radius, or its edge shows.
        CornerShade(Color(0xB3050608), 900f)
        val mod = Modifier.align(Alignment.BottomEnd).padding(end = 96.u, bottom = 120.u)
        if (s.auto) Row(
            mod.shadow(24.u, Shapes.pill).clip(Shapes.pill).background(Colors.Surface).padding(start = 34.u, top = 10.u, end = 10.u, bottom = 10.u),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.u),
        ) {
            BasicText(if (intro) "Заставка пропущена" else "Пересказ пропущен", style = text(Type.LARGE, 500))
            FillButton(Icons.Undo, "Вернуться", 1f, focused = true)
        }
        else {
            val span = (s.range.last - s.range.first).coerceAtLeast(1)
            val fill = ((c.position - s.range.first).toFloat() / span).coerceIn(0f, 1f)
            Box(mod.shadow(24.u, Shapes.pill)) {
                FillButton(Icons.SkipAhead, if (intro) "Пропустить заставку" else "Пропустить пересказ", fill, focused = true)
            }
        }
    }
}

/** A pill whose lighter part grows with [fill]: how far the intro or the countdown has got. */
@Composable
private fun FillButton(icon: ImageVector?, label: String, fill: Float, focused: Boolean) {
    val f = animateFloatAsState(fill, tween(260, easing = LinearEasing), label = "fill")
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    val done = if (focused) Colors.Focus else Colors.PaleStrong
    // The filled part is drawn, not laid out: growing every frame, it must not measure the button again.
    Box(Modifier.height(76.u).clip(Shapes.pill).background(if (focused) Color(0xFFDADDE3) else Colors.Pale)) {
        Box(Modifier.matchParentSize().drawBehind { drawRect(done, size = Size(size.width * f.value, size.height)) })
        Row(
            Modifier.padding(start = if (icon != null) 30.u else 36.u, end = 36.u).height(76.u),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.u),
        ) {
            icon?.let { Ico(it, 30.u, fg) }
            BasicText(label, style = text(Type.LARGE, 600, fg))
        }
    }
}

@Composable
private fun NextCard(c: PlayerControls) {
    val next = c.playback.items.getOrNull(c.index + 1) ?: return
    Box(Modifier.fillMaxSize()) {
        CornerShade(Color(0xCC050608), 1100f)
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 96.u, bottom = 96.u).width(780.u)
                .shadow(20.u, Shapes.menu).clip(Shapes.menu).background(Colors.Surface).padding(28.u),
            verticalArrangement = Arrangement.spacedBy(28.u),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.u)) {
                Box(Modifier.size(272.u, 153.u).clip(Shapes.card).background(Colors.Placeholder)) {
                    if (next.still != null) AsyncImage(next.still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else BasicText("${next.episode}", Modifier.align(Alignment.Center), style = text(Type.NUMERAL, 300, Colors.Faint))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.u)) {
                    BasicText("Следующая серия", style = text(Type.SMALL, 500, Colors.Muted))
                    BasicText("Серия ${next.episode}", style = text(Type.TITLE, 600))
                    val meta = listOfNotNull(next.name, next.runtimeMin.takeIf { it > 0 }?.let { "$it мин" }).joinToString(", ")
                    if (meta.isNotEmpty()) BasicText(meta, style = text(Type.SMALL, 400, Colors.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Modifier.align(Alignment.TopCenter).padding(top = 72.u).shadow(24.u, Shapes.menu).clip(Shapes.menu)
                .background(Colors.Surface).padding(horizontal = 32.u, vertical = 16.u),
            style = text(Type.LARGE, 500),
        )
    }
}
