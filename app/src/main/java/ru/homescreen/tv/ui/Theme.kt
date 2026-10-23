package ru.homescreen.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
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

object Colors {
    val Bg = Color(0xFF050608)
    val Fg = Color(0xFFF4F5F7)
    val Muted = Color(0xB3ECEFF5)
    val Faint = Color(0x6BECEFF5)
    val GlassLine = Color(0x29FFFFFF)
    val FocusFg = Color(0xFF0B0C0F)
    val Ok = Color(0xFF5FD38A)
    val Gold = Color(0xFFFFD36B)
    val Glass = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x0DFFFFFF)))
    val Focus = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE9EBEF)))
}

@Composable
fun text(size: Number, weight: Int = 400, color: Color = Colors.Fg) =
    TextStyle(fontFamily = Onest, fontWeight = FontWeight(weight), fontSize = size.t, color = color)

/** Hue-based colour like the mockup's `hsl(h, s%, l%)`. */
fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Color = Color.hsl(((h % 360f) + 360f) % 360f, s, l, a)
