package ru.homescreen.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import ru.homescreen.tv.R

/**
 * The layout is drawn on a 1920x1080 grid (the mockup's pixels). [LocalScale] turns those into dp for whatever the
 * TV reports, so `96.u` is the 5 % TV-safe margin on any panel.
 */
val LocalScale = staticCompositionLocalOf { 0.5f }

val Number.u: Dp
    @Composable @ReadOnlyComposable
    get() = (toFloat() * LocalScale.current).dp

val Number.t: TextUnit
    @Composable @ReadOnlyComposable
    get() = with(LocalDensity.current) { (toFloat() * LocalScale.current).dp.toSp() }

@OptIn(ExperimentalTextApi::class)
private fun onest(w: Int) = Font(R.font.onest, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Onest = FontFamily(onest(200), onest(300), onest(400), onest(500), onest(600), onest(700), onest(800), onest(900))

/** Every colour the screens use; a literal outside this object is a one-off (a brand dot, the weather icon). */
object Colors {
    val Bg = Color(0xFF050608)
    val Fg = Color(0xFFF4F5F7)
    /** Row titles. */
    val Title = Color(0xEBECEFF5)
    /** Overviews and other running text. */
    val Body = Color(0xDBF0F2F6)
    val Muted = Color(0xB3ECEFF5)
    val Faint = Color(0x6BECEFF5)
    /** Unselected tabs. */
    val Dim = Color(0x99FFFFFF)
    /** Text on the white focus fill, and the second line on it. */
    val FocusFg = Color(0xFF0B0C0F)
    val OnFocusMuted = Color(0x990B0C0F)
    /** The focus fill of buttons, pills and menu rows, and the ring around focused pictures. */
    val Focus = Color.White
    val FocusRing = Color(0xE6FFFFFF)
    /** Menus, pickers and toasts: opaque, so nothing behind them shows through. */
    val Surface = Color(0xFF16181D)
    /** Under a picture that is still loading. */
    val Placeholder = Color(0xFF15171C)
    /** An unfocused pill or key: flat, no gradient, no outline. */
    val Pale = Color(0x1FFFFFFF)
    val PaleStrong = Color(0x33FFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    /** Black text shadow over pictures. */
    val Shade = Color(0x99000000)
    val Ok = Color(0xFF5FD38A)
    val Gold = Color(0xFFFFD36B)
}

/**
 * The type scale on the 1920 grid (`text(16)` is 16 px of a 1080p panel). Nothing below [CAPTION]: smaller does not
 * read from the sofa. Display sizes (clocks, logos, the Showy code) are the only ones outside the scale.
 */
object Type {
    const val CAPTION = 22
    const val SMALL = 24
    const val BODY = 27
    const val LARGE = 30
    const val TITLE = 40
    const val HEADING = 44
    const val DISPLAY = 64
    const val CLOCK = 52
    const val NUMERAL = 72
}

/** Four corner sizes: pills (half the height), cards and pictures, menus, and small chips and badges. */
object Shapes {
    val pill = RoundedCornerShape(50)
    val small: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(10.u)
    val card: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(18.u)
    val menu: RoundedCornerShape @Composable @ReadOnlyComposable get() = RoundedCornerShape(30.u)
}

/**
 * One focus language: pictures and tiles grow by [TILE] with a white ring; buttons, pills, keys and tabs grow by
 * [CONTROL] and turn white; rows of lists and menus only turn white (a scaled row shifts its icons sideways).
 */
object FocusSpec {
    const val TILE = 1.1f
    const val CONTROL = 1.05f
    const val RING = 3
}

@Composable
fun text(size: Number, weight: Int = 400, color: Color = Colors.Fg) =
    TextStyle(fontFamily = Onest, fontWeight = FontWeight(weight), fontSize = size.t, color = color)

/** Hue-based colour like the mockup's `hsl(h, s%, l%)`. */
fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color = Color.hsl(((h % 360f) + 360f) % 360f, s, l, a)
