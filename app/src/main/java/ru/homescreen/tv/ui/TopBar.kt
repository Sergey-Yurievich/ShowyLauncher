package ru.homescreen.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import ru.homescreen.tv.Tab
import ru.homescreen.tv.data.Inputs
import ru.homescreen.tv.data.Place
import ru.homescreen.tv.data.TvInput
import ru.homescreen.tv.data.Weather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun Ico(v: ImageVector, size: Dp, tint: Color, modifier: Modifier = Modifier) =
    Image(v, null, modifier.size(size), colorFilter = ColorFilter.tint(tint))

private val TextShadow = Shadow(Color(0x99000000), blurRadius = 6f)
private val Dim = Color(0x99FFFFFF)

/**
 * Plain text over the picture: time and weather on the left, tabs in the middle, icons on the right. Focus is shown
 * by brightness, size and a short line under the item; nothing has a background except the focused round button.
 */
@Composable
fun TopBar(nav: Nav, weather: Weather?, place: Place, console: Pair<TvInput, Inputs.Console>?, visible: Boolean) {
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(500), label = "topA")
    val active = nav.zone == Zone.TOP && nav.layer == Layer.NONE
    Box(
        Modifier.fillMaxWidth().padding(start = 96.u, end = 96.u).offset(y = 50.u).height(80.u)
            // ModulateAlpha: no offscreen layer, so the fade does not crop the city line drawn below the bar.
            .graphicsLayer { alpha = a; translationY = (1 - a) * -20f * density; compositingStrategy = CompositingStrategy.ModulateAlpha },
    ) {
        Widget(weather, place, Modifier.align(Alignment.CenterStart))
        Tabs(nav, active, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(14.u), verticalAlignment = Alignment.CenterVertically) {
            IconItem(Icons.YouTube, "YouTube", active && nav.top == TopItem.YOUTUBE)
            console?.let { (input, kind) ->
                IconItem(if (kind == Inputs.Console.PLAYSTATION) Icons.PlayStation else Icons.Xbox, input.sub,
                    active && nav.top == TopItem.CONSOLE, live = input.connected == true)
            }
            IconItem(Icons.Inputs, "Входы", active && nav.top == TopItem.INPUTS)
            IconItem(Icons.Settings, "Настройки", active && nav.top == TopItem.SETTINGS)
        }
    }
}

@Composable
private fun Widget(weather: Weather?, place: Place, modifier: Modifier) {
    var now by remember { mutableStateOf(Date()) }
    var colon by remember { mutableStateOf(true) }
    // One recomposition per second for the colon, nothing per frame.
    LaunchedEffect(Unit) { while (true) { delay(1000); colon = !colon; now = Date() } }
    // The clock keeps the time zone of where the network puts the TV: the TV's own may be an hour off.
    val zone = remember(place.zone) { place.zoneId?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault() }
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = zone }.format(now)
    Box(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.u)) {
        val big = text(52, 300).merge(TextStyle(shadow = TextShadow))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(hm.substring(0, 2), style = big)
            BasicText(":", style = big, modifier = Modifier.graphicsLayer { alpha = if (colon) 1f else 0.3f })
            BasicText(hm.substring(3), style = big)
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.u)) {
                WeatherIcon(weather, Modifier.size(34.u, 28.u))
                BasicText(weather?.let { (if (it.temp > 0) "+" else "") + it.temp + "°" } ?: "", style = text(24, 500).merge(TextStyle(shadow = TextShadow)))
                weather?.text?.takeIf { it.isNotEmpty() }?.let { BasicText(it, style = text(20, 400, Colors.Muted).merge(TextStyle(shadow = TextShadow))) }
            }
            val date = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).apply { timeZone = zone }.format(now)
            BasicText(date.replaceFirstChar { it.uppercase() }, style = text(20, 400, Colors.Muted).merge(TextStyle(shadow = TextShadow)))
        }
    }
    // The city under the clock and the weather, below the row's own height: the clock stays level with the tabs.
    if (place.city.isNotEmpty()) BasicText(place.city, Modifier.align(Alignment.BottomStart).offset(y = 30.u),
        style = text(20, 400, Colors.Muted).merge(TextStyle(shadow = TextShadow)))
    }
}

/** Sun or moon, plus a cloud when it is cloudy or wet. Static: drawn once per weather update. */
@Composable
private fun WeatherIcon(w: Weather?, modifier: Modifier) {
    val cloudy = w == null || w.code >= 2
    val day = w?.isDay ?: false
    Canvas(modifier) {
        val s = size.height / 28f
        val c = Offset(size.width - 10f * s, 9f * s)
        if (day) drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF3C4), Color(0xFFFFC94D)), c, 9f * s), 8f * s, c)
        else drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF6DA), Color(0xFFE9CF85)), c, 8f * s), 7.5f * s, c)
        if (cloudy) {
            val cloud = Color(0xFFE6E9EF)
            drawRoundRect(cloud, Offset(0f, 14f * s), androidx.compose.ui.geometry.Size(28f * s, 12f * s), androidx.compose.ui.geometry.CornerRadius(6f * s))
            drawCircle(cloud, 7.5f * s, Offset(11f * s, 14.5f * s))
            drawCircle(cloud, 5.5f * s, Offset(19f * s, 16f * s))
        }
    }
}

@Composable
private fun Tabs(nav: Nav, active: Boolean, modifier: Modifier) {
    val items = listOf(TopItem.HOME to Tab.HOME.label, TopItem.MOVIES to Tab.MOVIES.label, TopItem.SERIES to Tab.SERIES.label,
        TopItem.APPS to Tab.APPS.label, TopItem.SEARCH to "", TopItem.BOOKMARKS to "")
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.u)) {
        for ((item, label) in items) {
            val focused = active && nav.top == item
            val current = nav.topIndexOf(nav.tab) == item
            val color by animateColorAsState(if (focused || current) Color.White else Dim, tween(200), label = "tc")
            val scale by animateFloatAsState(if (focused) 1.14f else 1f, spring(0.55f, Spring.StiffnessMedium), label = "tsc")
            val line by animateDpAsState(if (focused) 28.u else if (current) 14.u else 0.u, spring(0.6f, Spring.StiffnessMediumLow), label = "tl")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
                if (label.isEmpty()) Ico(if (item == TopItem.SEARCH) Icons.Search else Icons.Bookmark, 30.u, color, Modifier.padding(vertical = 2.u))
                else BasicText(label, style = text(27, if (current || focused) 600 else 500, color).merge(TextStyle(shadow = TextShadow)))
                Spacer(Modifier.height(8.u))
                Box(Modifier.width(line).height(4.u).clip(RoundedCornerShape(2.u)).background(Color.White))
            }
        }
    }
}

@Composable
private fun IconItem(icon: ImageVector, label: String, focused: Boolean, live: Boolean = false) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(0.5f, Spring.StiffnessMedium), label = "ib")
    val bg by animateColorAsState(if (focused) Color.White else Color.Transparent, tween(180), label = "ibg")
    val fg = if (focused) Colors.FocusFg else Color.White
    Row(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .height(64.u).clip(RoundedCornerShape(32.u)).background(bg).padding(horizontal = 17.u),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Ico(icon, 30.u, fg)
            if (live) LiveDot(focused)
        }
        AnimatedVisibility(focused, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
            Row {
                Spacer(Modifier.width(12.u))
                BasicText(label, style = text(22, 600, fg))
            }
        }
    }
}

/** Green pulse on the console icon while it is switched on (HDMI-CEC says so). */
@Composable
private fun LiveDot(focused: Boolean) {
    val inf = rememberInfiniteTransition(label = "live")
    val p by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "lp")
    Box(
        Modifier.offset(x = 4.u, y = 2.u).size(11.u).graphicsLayer { alpha = p }
            .clip(RoundedCornerShape(6.u)).background(if (focused) Color(0xFF1F9E57) else Colors.Ok),
    )
}
