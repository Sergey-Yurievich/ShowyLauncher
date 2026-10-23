package ru.homescreen.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage
import ru.homescreen.tv.WatchSession
import ru.homescreen.tv.WatchSession.Chip
import ru.homescreen.tv.WatchSession.Phase
import ru.homescreen.tv.WatchSession.Zone
import ru.homescreen.tv.data.Details
import ru.homescreen.tv.data.Showy
import ru.homescreen.tv.data.Tmdb

private const val ROWS = 7
/** Where the film's details end and the pills begin; the list and messages follow. */
private const val PILLS_Y = 340f
private const val LIST_Y = 430f
private const val ROW_H = 72f
private const val ROW_GAP = 8f

/**
 * The film's screen: logo, facts and overview on top, then source, season, voice (and trailer) as pills, then the
 * files. Sources are looked up as soon as it opens, so watching is one step from a poster.
 */
@Composable
fun WatchScreen(w: WatchSession?, d: Details?, visible: Boolean, bookmarked: Boolean, newEpisode: String?) {
    AnimatedVisibility(visible && w != null, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) {
        val s = w ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize().background(Colors.Bg)) {
            AsyncImage(Tmdb.img(s.title.backdrop ?: d?.backdrop, "w1280"), null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
                contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Color(0xFA050608), 0.5f to Color(0xE6050608), 1f to Color(0x8C050608))))

            Box(Modifier.offset(x = 96.u, y = 70.u)) { LogoTitle(s.title.name, Tmdb.img(d?.logo, "w500"), 76f, maxW = 820, maxH = 110) }
            Column(Modifier.offset(x = 96.u, y = 196.u).width(1040.u), verticalArrangement = Arrangement.spacedBy(12.u)) {
                MetaLine(heroOf(s.title, d, newEpisode), size = 23)
                BasicText(s.title.overview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = text(23, 400, Color(0xD6ECEFF5)).merge(TextStyle(lineHeight = 1.4.em)))
            }

            // The pills are there in every phase: a title with no sources (yet) can still be bookmarked.
            Pills(s, bookmarked)
            when (s.phase) {
                Phase.LOADING -> Message("Ищем источники…", null)
                Phase.AUTH -> Auth(s.code)
                Phase.DENIED, Phase.ERROR -> Message(s.message, if (s.phase == Phase.ERROR) "OK — попробовать ещё раз" else null)
                Phase.READY -> Ready(s)
            }
        }
    }
}

@Composable
private fun Message(text: String, hint: String?) {
    Column(Modifier.offset(x = 96.u, y = LIST_Y.u).widthIn(max = 1100.u), verticalArrangement = Arrangement.spacedBy(14.u)) {
        BasicText(text, style = text(30, 500))
        hint?.let { BasicText(it, style = text(24, 400, Colors.Muted)) }
    }
}

@Composable
private fun Auth(code: Int) {
    Row(Modifier.offset(x = 96.u, y = LIST_Y.u), horizontalArrangement = Arrangement.spacedBy(80.u)) {
        Column(Modifier.width(900.u), verticalArrangement = Arrangement.spacedBy(22.u)) {
            BasicText("Подключите Showy", style = text(44, 800))
            BasicText("Отправьте этот код боту @showybot в Telegram или наведите камеру телефона на QR-код. Экран обновится сам.",
                style = text(27, 400, Colors.Muted).merge(TextStyle(lineHeight = 1.4.em)))
            BasicText(if (code > 0) code.toString() else "······", style = text(110, 800).merge(TextStyle(letterSpacing = 0.12.em)))
        }
        if (code > 0) Box(Modifier.size(380.u).clip(RoundedCornerShape(28.u)).background(Color.White).padding(24.u)) {
            AsyncImage("http://api.qrserver.com/v1/create-qr-code/?size=400x400&margin=0&data=https://t.me/showybot?start=$code", null,
                Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Pills(s: WatchSession, bookmarked: Boolean) {
    Row(Modifier.offset(x = 80.u, y = PILLS_Y.u), horizontalArrangement = Arrangement.spacedBy(12.u), verticalAlignment = Alignment.CenterVertically) {
        s.chips.forEachIndexed { i, c ->
            ChoicePill(chipLabel(s, c, bookmarked), s.zone == Zone.CHIPS && s.chip == i && s.picker == null, s.picker == c,
                icon = when (c) {
                    Chip.TRAILER -> Icons.Trailer
                    Chip.BOOKMARK -> if (bookmarked) Icons.BookmarkOn else Icons.Bookmark
                    else -> null
                })
        }
        if (s.busy) BasicText("Загрузка…", modifier = Modifier.padding(start = 16.u), style = text(24, 400, Colors.Muted))
    }
}

@Composable
private fun Ready(s: WatchSession) {

    val list = s.list
    val focus = if (s.zone == Zone.LIST && s.picker == null) s.idx else -1
    s.marks // progress bars follow ViMu results
    Column(Modifier.offset(x = 80.u, y = LIST_Y.u), verticalArrangement = Arrangement.spacedBy(ROW_GAP.u)) {
        val first = (s.idx - 3).coerceIn(0, (list.size - ROWS).coerceAtLeast(0))
        for (i in first until (first + ROWS).coerceAtMost(list.size)) {
            val item = list[i]
            if (s.files.isEmpty()) SimilarRow(s, item, i == focus) else FileRow(s, item, i == focus)
        }
        if (s.note.isNotEmpty()) BasicText(s.note, modifier = Modifier.padding(start = 16.u, top = 12.u), style = text(25, 400, Colors.Muted))
    }

    s.picker?.let { Picker(s, it) }
}

private fun chipLabel(s: WatchSession, c: Chip, bookmarked: Boolean): String = when (c) {
    Chip.SOURCE -> s.source?.name?.substringBefore(" ~ ") ?: "Источник"
    Chip.SEASON -> s.seasons.getOrNull(s.season)?.text ?: "Сезон"
    Chip.VOICE -> s.voices.getOrNull(s.voice)?.text ?: "Озвучка"
    Chip.TRAILER -> "Трейлер"
    Chip.BOOKMARK -> if (bookmarked) "В закладках" else "В закладки"
}

@Composable
private fun ChoicePill(label: String, focused: Boolean, open: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    Row(
        Modifier.height(64.u).clip(RoundedCornerShape(32.u))
            .background(if (focused) Color.White else if (open) Color(0x33FFFFFF) else Color(0x1AFFFFFF))
            .padding(start = 28.u, end = 20.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.u),
    ) {
        // An action pill (the trailer) leads with its icon; a choice ends with the chevron of its list.
        icon?.let { Ico(it, 28.u, fg) }
        BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.u), style = text(24, 600, fg))
        if (icon == null) Ico(Icons.Down, 26.u, fg)
    }
}

@Composable
private fun FileRow(s: WatchSession, item: Showy.Item, focused: Boolean) {
    val mark = s.mark(item)
    val ep = s.episode(item)
    if (focused) LaunchedEffect(item.url) { s.probe(item) }
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    val sub = if (focused) Color(0x990B0C0F) else Colors.Muted
    val shape = RoundedCornerShape((ROW_H / 2).u)
    val name = ep?.name?.trim()?.takeIf { !Tmdb.genericEpisode(it) }
    Box(Modifier.width(1040.u).height(ROW_H.u).clip(shape).background(if (focused) Color.White else Color.Transparent)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.u), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.u)) {
            if (name != null) {
                BasicText(item.episode.toString(), modifier = Modifier.width(40.u), style = text(25, 500, sub))
                BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = text(27, 500, fg))
            } else {
                BasicText(item.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = text(27, 500, fg))
            }
            ep?.runtime?.takeIf { it > 0 }?.let { BasicText("$it мин", style = text(21, 400, sub)) }
            item.hdr?.let { BasicText(it, style = text(21, 500, sub)) }
            item.sizeGb?.let { BasicText(gb(it), style = text(21, 600, fg)) }
            s.quality(item).takeIf { it.isNotEmpty() }?.let { BasicText(it, style = text(21, 500, sub)) }
            ep?.vote?.takeIf { it > 0 }?.let { v ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
                    Ico(Icons.Star, 20.u, if (focused) Color(0xFFD99A00) else Colors.Gold)
                    BasicText("%.1f".format(v), style = text(21, 600, fg))
                }
            }
            if (mark?.done == true) Ico(Icons.Check, 30.u, fg)
        }
        val p = mark?.progress ?: 0f
        if (p > 0.01f && mark?.done != true) Box(
            Modifier.align(Alignment.BottomStart).padding(start = 28.u, end = 28.u, bottom = 10.u).fillMaxWidth().height(4.u)
                .clip(RoundedCornerShape(2.u)).background(if (focused) Color(0x260B0C0F) else Color(0x33FFFFFF)),
        ) { Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(if (focused) Colors.FocusFg else Color.White)) }
    }
}

@Composable
private fun SimilarRow(s: WatchSession, item: Showy.Item, focused: Boolean) {
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    val sub = if (focused) Color(0x990B0C0F) else Colors.Muted
    Row(
        Modifier.width(1040.u).height(ROW_H.u).clip(RoundedCornerShape((ROW_H / 2).u)).background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = 28.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.u),
    ) {
        BasicText(item.json.optString("title").ifEmpty { item.text }, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f), style = text(27, 500, fg))
        // A series' torrent shows its facts as a film's variant does; a search result its year and details.
        val size = item.sizeGb
        if (size != null) {
            item.hdr?.let { BasicText(it, style = text(21, 500, sub)) }
            BasicText(gb(size), style = text(21, 600, fg))
            s.quality(item).takeIf { it.isNotEmpty() }?.let { BasicText(it, style = text(21, 500, sub)) }
        } else listOf(item.year, item.details).filter { it.isNotEmpty() }.joinToString(" · ").takeIf { it.isNotEmpty() }
            ?.let { BasicText(it, maxLines = 1, style = text(21, 400, sub)) }
    }
}

private fun gb(v: Double) = if (v >= 10) "%.0f ГБ".format(v) else "%.1f ГБ".format(v)

@Composable
private fun Picker(s: WatchSession, c: Chip) {
    val labels: List<Pair<String, String>> = when (c) {
        Chip.SOURCE -> s.sources.map { it.name.substringBefore(" ~ ") to it.name.substringAfter(" ~ ", "") }
        Chip.SEASON -> s.seasons.map { it.text to "" }
        Chip.VOICE -> s.voices.map { it.text to "" }
        Chip.TRAILER, Chip.BOOKMARK -> emptyList()
    }
    val current = when (c) {
        Chip.SOURCE -> s.sources.indexOf(s.source)
        Chip.SEASON -> s.season
        Chip.VOICE -> s.voice
        Chip.TRAILER, Chip.BOOKMARK -> -1
    }
    val rows = 9
    val shape = RoundedCornerShape(30.u)
    Column(
        Modifier.offset(x = 80.u, y = (LIST_Y - 10f).u).width(640.u).shadow(40.u, shape).clip(shape).background(Color(0xF5181A1F)).padding(12.u),
        verticalArrangement = Arrangement.spacedBy(4.u),
    ) {
        val first = (s.pickIdx - 4).coerceIn(0, (labels.size - rows).coerceAtLeast(0))
        for (i in first until (first + rows).coerceAtMost(labels.size)) {
            val (name, extra) = labels[i]
            val f = i == s.pickIdx
            val fg = if (f) Colors.FocusFg else Colors.Fg
            Row(
                Modifier.fillMaxWidth().height(60.u).clip(RoundedCornerShape(22.u)).background(if (f) Color.White else Color.Transparent)
                    .padding(horizontal = 22.u),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.u),
            ) {
                BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = text(24, 500, fg))
                if (extra.isNotEmpty()) BasicText(extra, style = text(19, 400, if (f) Color(0x990B0C0F) else Colors.Muted))
                if (i == current) Ico(Icons.Check, 26.u, fg)
            }
        }
        if (c == Chip.SOURCE && !s.sourcesDone) {
            Spacer(Modifier.height(4.u))
            BasicText("Ищем ещё…", modifier = Modifier.padding(horizontal = 22.u, vertical = 10.u), style = text(21, 400, Colors.Muted))
        }
    }
}
