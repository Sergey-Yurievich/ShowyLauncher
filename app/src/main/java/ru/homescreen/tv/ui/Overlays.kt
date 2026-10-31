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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import ru.homescreen.tv.data.Person
import ru.homescreen.tv.data.PlayerPrefs
import ru.homescreen.tv.data.Showy
import ru.homescreen.tv.data.Title
import ru.homescreen.tv.data.Tmdb
import ru.homescreen.tv.data.TvInput

/** Opaque dropdown under the Входы button: HDMI ports with what is plugged in, then the tuner. */
@Composable
fun InputsMenu(visible: Boolean, inputs: List<TvInput>, focus: Int) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)) + scaleIn(tween(280), 0.96f, TransformOrigin(0.7f, 0f)), exit = fadeOut(tween(150))) {
        val shape = Shapes.menu
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.offset(x = 1250.u, y = 140.u).width(480.u)
                    .shadow(20.u, shape)
                    .clip(shape)
                    .background(Colors.Surface)
                    .padding(14.u),
                verticalArrangement = Arrangement.spacedBy(6.u),
            ) {
                if (inputs.isEmpty()) BasicText("Входы не найдены", modifier = Modifier.padding(20.u), style = text(Type.SMALL, 500, Colors.Muted))
                inputs.forEachIndexed { i, input ->
                    val f = i == focus
                    val fg = if (f) Colors.FocusFg else Colors.Fg
                    Row(
                        Modifier.fillMaxWidth().clip(Shapes.card)
                            .background(if (f) Color.White else Color.Transparent)
                            .padding(horizontal = 20.u, vertical = 16.u),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.u),
                    ) {
                        Ico(if (input.isTuner) Icons.Antenna else Icons.Inputs, 34.u, fg)
                        Column(Modifier.weight(1f)) {
                            BasicText(input.label, style = text(Type.SMALL, 500, fg))
                            BasicText(input.sub, style = text(Type.CAPTION, 400, if (f) Colors.OnFocusMuted else Colors.Muted))
                        }
                        val dot = when (input.connected) {
                            true -> Colors.Ok
                            else -> Colors.PaleStrong
                        }
                        Box(Modifier.size(12.u).clip(Shapes.pill).background(dot))
                    }
                }
            }
        }
    }
}

enum class SettingsKind { UPDATE, SERVER, SKIP, LINK, UNLINK, UNHIDE, ANDROID }

/** A line of the gear menu; the menu and the remote handling build the same list ([settingsRows]). */
data class SettingsRow(val kind: SettingsKind, val server: Showy.Server? = null)

fun settingsRows(linked: Boolean, hidden: Boolean): List<SettingsRow> = buildList {
    add(SettingsRow(SettingsKind.UPDATE))
    Showy.Server.entries.forEach { add(SettingsRow(SettingsKind.SERVER, it)) }
    add(SettingsRow(SettingsKind.SKIP))
    add(SettingsRow(SettingsKind.LINK))
    if (linked) add(SettingsRow(SettingsKind.UNLINK))
    if (hidden) add(SettingsRow(SettingsKind.UNHIDE))
    add(SettingsRow(SettingsKind.ANDROID))
}

/**
 * The gear's dropdown: this version and a newer one when there is one (else a check on request), where films are looked for (the Showy server), the built-in
 * player's intro skipping, the TVs sharing this one's history, hidden apps to bring back, then Android's own settings.
 */
@Composable
fun SettingsMenu(
    visible: Boolean, rows: List<SettingsRow>, current: Showy.Server, focus: Int, update: String?, updating: Boolean,
    checking: Boolean, pairing: Boolean, peers: List<String>, hidden: Int,
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(200)) + scaleIn(tween(280), 0.96f, TransformOrigin(0.9f, 0f)), exit = fadeOut(tween(150))) {
        val shape = Shapes.menu
        Box(Modifier.fillMaxSize()) {
            // Linked TVs and hidden apps make it taller than the screen: it scrolls after the focus (see [MenuRow]).
            Column(
                Modifier.offset(x = 1304.u, y = 140.u).width(520.u).heightIn(max = 900.u)
                    .shadow(20.u, shape)
                    .clip(shape)
                    .background(Colors.Surface)
                    .verticalScroll(rememberScrollState())
                    .padding(14.u),
                verticalArrangement = Arrangement.spacedBy(6.u),
            ) {
                rows.forEachIndexed { i, r ->
                    val f = i == focus
                    val prev = rows.getOrNull(i - 1)?.kind
                    when (r.kind) {
                        SettingsKind.UPDATE -> {
                            val version = "Версия ${ru.homescreen.tv.BuildConfig.VERSION_NAME}"
                            when {
                                update != null -> MenuRow(if (updating) "Скачиваю $update…" else "Обновить до $update", version, Icons.Down, f, false)
                                checking -> MenuRow("Проверяю обновления…", version, Icons.Refresh, f, false)
                                else -> MenuRow("Проверить обновления", version, Icons.Refresh, f, false)
                            }
                        }
                        SettingsKind.SERVER -> {
                            if (prev != SettingsKind.SERVER) {
                                if (prev != null) MenuDivider()
                                MenuHeader("Источник фильмов")
                            }
                            val s = r.server ?: return@forEachIndexed
                            MenuRow(s.label, s.host.substringAfter("//"), null, f, s == current)
                        }
                        SettingsKind.SKIP -> {
                            MenuDivider()
                            MenuHeader("Плеер")
                            MenuRow("Пропускать заставки сами", "С кнопкой «Вернуться»", Icons.SkipAhead, f, PlayerPrefs.skipIntros)
                        }
                        SettingsKind.LINK -> {
                            MenuDivider()
                            MenuHeader("Другие ТВ")
                            when {
                                pairing -> MenuRow("Ищу другой ТВ…", "Выберите то же на втором ТВ", Icons.Link, f, false)
                                peers.isEmpty() -> MenuRow("Связать с другим ТВ", "Общие история и закладки", Icons.Link, f, false)
                                else -> MenuRow("Связать ещё один ТВ", "Связано: " + peers.joinToString(", "), Icons.Link, f, false)
                            }
                        }
                        SettingsKind.UNLINK -> MenuRow("Отвязать этот ТВ", null, Icons.Close, f, false)
                        SettingsKind.UNHIDE -> {
                            MenuDivider()
                            MenuRow("Вернуть скрытые приложения", plural(hidden, "приложение", "приложения", "приложений"), Icons.Hide, f, false)
                        }
                        SettingsKind.ANDROID -> {
                            if (prev != SettingsKind.UNHIDE) MenuDivider()
                            MenuRow("Настройки Android", null, Icons.Settings, f, false)
                        }
                    }
                }
            }
        }
    }
}

/** Long press on OK: what can be done with the focused title, app or episode, as a small panel in the middle. */
@Composable
fun ContextMenu(visible: Boolean, items: List<MenuItem>, focus: Int) {
    AnimatedVisibility(visible, enter = fadeIn(tween(160)) + scaleIn(tween(220), 0.96f), exit = fadeOut(tween(120))) {
        val shape = Shapes.menu
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(600.u).shadow(20.u, shape).clip(shape).background(Colors.Surface).padding(14.u),
                verticalArrangement = Arrangement.spacedBy(6.u),
            ) {
                items.forEachIndexed { i, item -> MenuRow(item.label, null, item.icon, i == focus, false) }
            }
        }
    }
}

@Composable
private fun MenuHeader(label: String) {
    BasicText(label, modifier = Modifier.padding(start = 20.u, top = 10.u, bottom = 4.u), style = text(Type.CAPTION, 500, Colors.Muted))
}

@Composable
private fun MenuDivider() {
    Box(Modifier.padding(horizontal = 20.u, vertical = 6.u).fillMaxWidth().height(1.u).background(Colors.Hairline))
}

@Composable
private fun MenuRow(label: String, sub: String?, icon: ImageVector?, focused: Boolean, checked: Boolean) {
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    val view = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) view.bringIntoView() }
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(view).clip(Shapes.card)
            .background(if (focused) Color.White else Color.Transparent)
            .padding(horizontal = 20.u, vertical = 16.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.u),
    ) {
        icon?.let { Ico(it, 30.u, fg) }
        Column(Modifier.weight(1f)) {
            BasicText(label, style = text(Type.SMALL, 500, fg))
            sub?.let { BasicText(it, style = text(Type.CAPTION, 400, if (focused) Colors.OnFocusMuted else Colors.Muted)) }
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

/** «Смотреть все»: the whole collection as a poster grid. An actor's films have the actor's photo by the title. */
@Composable
fun GridScreen(
    visible: Boolean, title: String, items: List<Title>, focus: Int, loading: Boolean, accent: State<Color>, person: Person? = null,
    badge: (Title) -> String?,
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) {
        // Seven posters fill the width between the margins (as many as the remote moves by, see gridMove).
        val cols = 7
        val rowH = GRID_H + 44f
        val fr = focus / cols
        val y by animateFloatAsState(-((fr - 1).coerceAtLeast(0)) * rowH, tween(450, easing = FastOutSlowInEasing), label = "gy")
        // Opaque: the top bar and the hero behind would show through the header.
        Box(Modifier.fillMaxSize().background(Colors.Bg)) {
            Row(Modifier.offset(x = 96.u, y = if (person != null) 50.u else 70.u),
                verticalAlignment = if (person != null) Alignment.CenterVertically else Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.u)) {
                person?.let { Avatar(it, 96f) }
                BasicText(title, style = text(Type.HEADING, 800))
                if (loading) BasicText("Загружаем ещё…", modifier = Modifier.padding(bottom = 6.u), style = text(Type.CAPTION, 400, Colors.Muted))
            }
            // Clipped below the title (the clip is outside the scroll): rows scrolled up must not run over it.
            Box(Modifier.fillMaxSize().padding(top = 150.u).clipToBounds().graphicsLayer { translationY = y * LocalScaleHolder.px }.padding(top = 30.u)) {
                // Only the rows around the focus are composed; an actor's films can run to a few hundred.
                val firstRow = (fr - 2).coerceAtLeast(0)
                val lastRow = minOf(fr + 3, (items.size - 1) / cols)
                for (ri in firstRow..lastRow) {
                    Row(
                        Modifier.offset(x = 96.u, y = (ri * rowH).u),
                        horizontalArrangement = Arrangement.spacedBy(GRID_GAP.u),
                    ) {
                        for (i in ri * cols until minOf((ri + 1) * cols, items.size)) {
                            val t = items[i]
                            androidx.compose.runtime.key(t.key) {
                                FocusPoster(t, i == focus, GRID_W, GRID_H, accent, badge)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val GRID_W = 220f
private const val GRID_H = 330f
private const val GRID_GAP = 31f

/**
 * A poster in a grid or a row off Home: it grows and glows in the film's colour only while focused. Unfocused it has
 * no shadow at all: a shadow on each of thirty posters cost the TV's GPU more than the pictures.
 */
@Composable
fun FocusPoster(t: Title, focused: Boolean, w: Float, h: Float, accent: State<Color>, badge: (Title) -> String?) {
    val p = animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "fp")
    val shape = Shapes.card
    val px = LocalScaleHolder.px
    Box(
        Modifier.size(w.u, h.u).graphicsLayer {
            val s = 1f + (FocusSpec.TILE - 1f) * p.value
            scaleX = s; scaleY = s
            shadowElevation = 30f * p.value * px
            ambientShadowColor = accent.value; spotShadowColor = accent.value
            this.shape = shape; clip = true
        }.background(Colors.Placeholder),
    ) {
        AsyncImage(Tmdb.img(t.poster, "w342"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        badge(t)?.let { NewEpisodeBadge(it) }
        if (focused) Box(Modifier.fillMaxSize().border(FocusSpec.RING.u, Colors.FocusRing, shape))
    }
}

/**
 * Linking two TVs: the same four digits on both, and the key is handed over only after «Да» on each. [focus] 0 is
 * «Да, связать», 1 «Отмена»; once this TV said yes ([Sync.Confirm.waiting]) only «Отмена» is left.
 */
@Composable
fun PairCode(c: ru.homescreen.tv.data.Sync.Confirm?, focus: Int) {
    AnimatedVisibility(c != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
        val shown = c ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize().background(Colors.Shade), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(720.u).shadow(30.u, Shapes.menu).clip(Shapes.menu).background(Colors.Surface)
                    .padding(start = 56.u, end = 56.u, top = 56.u, bottom = 44.u),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.u),
            ) {
                BasicText("Связать с «${shown.name}»", maxLines = 1, overflow = TextOverflow.Ellipsis, style = text(Type.LARGE, 600))
                BasicText(shown.code, style = text(Type.CODE, 800, if (shown.waiting) Colors.Faint else Colors.Fg).merge(TextStyle(letterSpacing = 0.2.em)))
                BasicText(if (shown.waiting) "Подтвердите на другом ТВ" else "Такой же код на другом ТВ?", style = text(Type.SMALL, 400, Colors.Muted))
                Row(Modifier.padding(top = 8.u), horizontalArrangement = Arrangement.spacedBy(20.u)) {
                    if (!shown.waiting) PairButton("Да, связать", focus == 0)
                    PairButton("Отмена", shown.waiting || focus == 1)
                }
            }
        }
    }
}

@Composable
private fun PairButton(label: String, focused: Boolean) {
    Box(
        Modifier.graphicsLayer { val s = if (focused) FocusSpec.CONTROL else 1f; scaleX = s; scaleY = s }
            .clip(Shapes.pill).background(if (focused) Colors.Focus else Colors.Pale)
            .padding(horizontal = 44.u, vertical = 18.u),
    ) {
        BasicText(label, style = text(Type.BODY, if (focused) 600 else 500, if (focused) Colors.FocusFg else Colors.Fg))
    }
}

@Composable
fun Toast(nav: Nav) {
    LaunchedEffect(nav.toast) {
        if (nav.toast != null) { delay(2400); nav.toast = null }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(nav.toast != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Box(Modifier.padding(bottom = 70.u).clip(Shapes.pill).background(Colors.Surface).padding(horizontal = 30.u, vertical = 18.u)) {
                BasicText(nav.toast ?: "", style = text(Type.SMALL, 500))
            }
        }
    }
}
