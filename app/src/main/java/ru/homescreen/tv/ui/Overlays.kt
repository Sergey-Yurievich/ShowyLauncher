package ru.homescreen.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import ru.homescreen.tv.data.Details
import ru.homescreen.tv.data.Showy
import ru.homescreen.tv.data.Title
import ru.homescreen.tv.data.Tmdb
import ru.homescreen.tv.data.TvInput

/** Glass dropdown under the Входы button: HDMI ports with what is plugged in, then the tuner. */
@Composable
fun InputsMenu(visible: Boolean, inputs: List<TvInput>, focus: Int) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)) + scaleIn(tween(280), 0.96f, TransformOrigin(0.7f, 0f)), exit = fadeOut(tween(150))) {
        val shape = RoundedCornerShape(30.u)
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.offset(x = 1250.u, y = 140.u).width(480.u)
                    .shadow(40.u, shape)
                    .clip(shape)
                    .background(Color(0xF5181A1F))
                    .padding(14.u),
                verticalArrangement = Arrangement.spacedBy(6.u),
            ) {
                if (inputs.isEmpty()) BasicText("Входы не найдены", modifier = Modifier.padding(20.u), style = text(24, 500, Colors.Muted))
                inputs.forEachIndexed { i, input ->
                    val f = i == focus
                    val fg = if (f) Colors.FocusFg else Colors.Fg
                    val sc by animateFloatAsState(if (f) 1.03f else 1f, label = "in")
                    Row(
                        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(20.u))
                            .background(if (f) Color.White else Color.Transparent)
                            .padding(horizontal = 20.u, vertical = 16.u),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.u),
                    ) {
                        Ico(if (input.isTuner) Icons.Antenna else Icons.Inputs, 34.u, fg)
                        Column(Modifier.weight(1f)) {
                            BasicText(input.label, style = text(24, 500, fg))
                            BasicText(input.sub, style = text(19, 400, if (f) Color(0x990B0C0F) else Colors.Muted))
                        }
                        val dot = when (input.connected) {
                            true -> Colors.Ok
                            else -> Color(0x33FFFFFF)
                        }
                        Box(Modifier.size(12.u).clip(RoundedCornerShape(6.u)).background(dot))
                    }
                }
            }
        }
    }
}

/** The gear's dropdown: where films are looked for (the Showy server), then Android's own settings. */
@Composable
fun SettingsMenu(visible: Boolean, current: Showy.Server, focus: Int, update: String?, updating: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)) + scaleIn(tween(280), 0.96f, TransformOrigin(0.9f, 0f)), exit = fadeOut(tween(150))) {
        val shape = RoundedCornerShape(30.u)
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.offset(x = 1304.u, y = 140.u).width(520.u)
                    .shadow(40.u, shape)
                    .clip(shape)
                    .background(Color(0xF5181A1F))
                    .padding(14.u),
                verticalArrangement = Arrangement.spacedBy(6.u),
            ) {
                // A newer release first: the menu opens on it.
                val shift = if (update != null) 1 else 0
                if (update != null) {
                    MenuRow(if (updating) "Скачиваю $update…" else "Обновить до $update", null, Icons.Down, focus == 0, false)
                    Box(Modifier.padding(horizontal = 20.u, vertical = 6.u).fillMaxWidth().height(1.u).background(Color(0x1FFFFFFF)))
                }
                BasicText("Источник фильмов", modifier = Modifier.padding(start = 20.u, top = 10.u, bottom = 4.u), style = text(19, 500, Colors.Muted))
                Showy.Server.entries.forEachIndexed { i, s ->
                    MenuRow(s.label, s.host.substringAfter("//"), null, i + shift == focus, s == current)
                }
                Box(Modifier.padding(horizontal = 20.u, vertical = 6.u).fillMaxWidth().height(1.u).background(Color(0x1FFFFFFF)))
                MenuRow("Настройки Android", null, Icons.Settings, focus == Showy.Server.entries.size + shift, false)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, sub: String?, icon: ImageVector?, focused: Boolean, checked: Boolean) {
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    val sc by animateFloatAsState(if (focused) 1.03f else 1f, label = "mr")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(20.u))
            .background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = 20.u, vertical = 16.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.u),
    ) {
        icon?.let { Ico(it, 30.u, fg) }
        Column(Modifier.weight(1f)) {
            BasicText(label, style = text(24, 500, fg))
            sub?.let { BasicText(it, style = text(19, 400, if (focused) Color(0x990B0C0F) else Colors.Muted)) }
        }
        if (checked) Ico(Icons.Check, 28.u, fg)
    }
}

fun heroOf(t: Title, d: Details?, chip: String? = null): HeroData {
    val genres = d?.genres?.take(2)?.joinToString(", ")
        ?: t.genreIds.mapNotNull { ru.homescreen.tv.data.Catalog.GENRES[it] }.take(2).joinToString(", ")
    val length = when {
        d == null -> null
        d.seasons > 0 -> plural(d.seasons, "сезон", "сезона", "сезонов")
        d.runtime > 0 -> if (d.runtime >= 60) "${d.runtime / 60} ч ${d.runtime % 60} мин" else "${d.runtime} мин"
        else -> null
    }
    return HeroData(
        key = t.key, name = t.name, logo = Tmdb.img(d?.logo, "w500"), vote = t.vote,
        meta = listOfNotNull(t.year.takeIf { it.isNotEmpty() }, genres.takeIf { it.isNotEmpty() }, length),
        age = d?.age, chip = chip, overview = t.overview,
    )
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}

/** «Смотреть все»: the whole collection as a poster grid. */
@Composable
fun GridScreen(visible: Boolean, title: String, items: List<Title>, focus: Int, loading: Boolean, accent: State<Color>, badge: (Title) -> String?) {
    AnimatedVisibility(visible, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) {
        val cols = 7
        val rowH = 300f + 44f
        val fr = focus / cols
        val y by animateFloatAsState(-((fr - 1).coerceAtLeast(0)) * rowH, tween(450, easing = FastOutSlowInEasing), label = "gy")
        Box(Modifier.fillMaxSize().background(Color(0xF2050608))) {
            Row(Modifier.offset(x = 96.u, y = 70.u), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.u)) {
                BasicText(title, style = text(44, 800))
                if (loading) BasicText("Загружаем ещё…", modifier = Modifier.padding(bottom = 6.u), style = text(22, 400, Colors.Muted))
            }
            // Clipped below the title (the clip is outside the scroll): rows scrolled up must not run over it.
            Box(Modifier.fillMaxSize().padding(top = 150.u).clipToBounds().graphicsLayer { translationY = y * LocalScaleHolder.px }.padding(top = 30.u)) {
                val firstRow = (fr - 2).coerceAtLeast(0)
                val lastRow = fr + 3
                items.chunked(cols).forEachIndexed { ri, line ->
                    if (ri in firstRow..lastRow) Row(
                        Modifier.offset(x = 96.u, y = (ri * rowH).u),
                        horizontalArrangement = Arrangement.spacedBy(30.u),
                    ) {
                        line.forEachIndexed { ci, t ->
                            val f = ri * cols + ci == focus
                            val sc by animateFloatAsState(if (f) 1.1f else 1f, label = "gs")
                            val shape = RoundedCornerShape(18.u)
                            Box(
                                Modifier.size(200.u, 300.u).graphicsLayer { scaleX = sc; scaleY = sc }
                                    .shadow(if (f) 30.u else 8.u, shape, ambientColor = if (f) accent.value else Color.Black, spotColor = if (f) accent.value else Color.Black)
                                    .clip(shape).background(Color(0xFF15171C)),
                            ) {
                                AsyncImage(Tmdb.img(t.poster, "w342"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                badge(t)?.let { NewEpisodeBadge(it) }
                                if (f) Box(Modifier.fillMaxSize().border(3.u, Color(0xE6FFFFFF), shape))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Toast(nav: Nav) {
    LaunchedEffect(nav.toast) {
        if (nav.toast != null) { delay(2400); nav.toast = null }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(nav.toast != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Box(Modifier.padding(bottom = 70.u).clip(RoundedCornerShape(34.u)).background(Color(0xF01A1C22)).padding(horizontal = 30.u, vertical = 18.u)) {
                BasicText(nav.toast ?: "", style = text(24, 500))
            }
        }
    }
}
