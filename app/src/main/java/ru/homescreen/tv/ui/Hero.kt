package ru.homescreen.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.request.ImageRequest
import androidx.compose.ui.unit.em
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** What the big block shows for the focused title, an app or a collection. */
data class HeroData(
    val key: String,
    val name: String,
    val logo: String?,
    val vote: Double,
    val meta: List<String>,
    val age: String?,
    val chip: String?,
    val overview: String,
)

fun logoSize(name: String, base: Float): Float {
    val n = name.length
    return when {
        n <= 8 -> base
        n <= 14 -> base * 0.82f
        n <= 22 -> base * 0.66f
        else -> base * 0.52f
    }
}

@Composable
fun LogoTitle(name: String, logo: String?, base: Float, maxW: Int = 980, maxH: Int = 170) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) {
        val ctx = LocalContext.current
        // Software bitmap: its pixels are read once to tell a dark logo, which is then drawn white (alpha kept).
        val request = remember(logo) { ImageRequest.Builder(ctx).data(logo).allowHardware(false).build() }
        var dark by remember(logo) { mutableStateOf(LogoTone.known[logo] ?: false) }
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            colorFilter = if (dark) LogoTone.White else null,
            onError = { failed = true },
            onSuccess = { st -> dark = LogoTone.isDark(logo, (st.result.drawable as? BitmapDrawable)?.bitmap) },
            modifier = Modifier.widthIn(max = (maxW * 0.72f).u).heightIn(max = maxH.u).height(maxH.u),
        )
    } else {
        BasicText(
            name.uppercase(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxW.u),
            style = text(logoSize(name, base), 900).merge(
                TextStyle(letterSpacing = 0.04.em, lineHeight = 0.92.em, shadow = Shadow(Colors.Shade, blurRadius = 6f)),
            ),
        )
    }
}

@Composable
fun MetaLine(d: HeroData, size: Int = Type.SMALL) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.u)) {
        if (d.vote > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.u)) {
            Ico(Icons.Star, 30.u, Colors.Gold)
            BasicText("%.1f".format(d.vote), style = text(size, 700))
        }
        for (m in d.meta) BasicText(m, style = text(size, 400, Colors.Muted).merge(TextStyle(shadow = Shadow(Colors.Shade, blurRadius = 6f))))
        d.age?.takeIf { it.isNotEmpty() }?.let { Chip(it, accent = null) }
        d.chip?.let { Chip(it, accent = Colors.Fg) }
    }
}

@Composable
fun Chip(label: String, accent: Color?) {
    // Flat: a plain fill, no gradient and no outline.
    Box(
        Modifier.clip(Shapes.small)
            .background(if (accent != null) Colors.PaleStrong else Colors.Pale)
            .padding(horizontal = 13.u, vertical = 4.u),
    ) { BasicText(label, style = text(Type.CAPTION, 600)) }
}

/**
 * Big block: logo, meta, two lines of overview and, on the hero row, the Смотреть button with the carousel
 * dots. Shrinks up when the rows take over and steps aside in trailer mode.
 */
@Composable
fun Hero(d: HeroData?, rowsMode: Boolean, trailer: Boolean, focused: Boolean, slides: Int, slide: Int) {
    val ty = animateFloatAsState(
        when {
            rowsMode && trailer -> -40f
            rowsMode -> -116f
            trailer -> 60f
            else -> 0f
        }, tween(600, easing = FastOutSlowInEasing), label = "hy",
    )
    val sc = animateFloatAsState(
        when {
            rowsMode && trailer -> 0.7f
            rowsMode -> 0.78f
            trailer -> 0.86f
            else -> 1f
        }, tween(600, easing = FastOutSlowInEasing), label = "hs",
    )
    val textA = animateFloatAsState(if (trailer) 0f else 1f, tween(450), label = "hta")
    val btnA = animateFloatAsState(if (rowsMode) 0f else 1f, tween(400), label = "hba")
    // In the rows the buttons are invisible: once faded out they leave the composition, so a new title there builds
    // and measures less (a hero change was the one frame that still dropped after a fast scroll).
    val buttons by produceState(!rowsMode, rowsMode) {
        if (rowsMode) { delay(450); value = false } else value = true
    }

    // Animated values are read inside graphicsLayer only: the block moves without recomposing.
    // The block starts 16 units left of the margin and the column pads it back: a fading layer is cut to its own
    // bounds, and the focused pill grows past the column's left and bottom edges.
    Box(
        Modifier.offset(x = 80.u, y = 300.u).width(1016.u)
            .graphicsLayer {
                scaleX = sc.value; scaleY = sc.value; transformOrigin = TransformOrigin(16f / 1016f, 0f)
                translationY = ty.value * LocalScaleHolder.px
            },
    ) {
        AnimatedContent(
            targetState = d,
            contentKey = { it?.key },
            transitionSpec = {
                (fadeIn(tween(450, 60)) + slideInVertically(tween(450, 60)) { it / 14 }) togetherWith fadeOut(tween(200)) using
                    SizeTransform(clip = false)
            },
            label = "hero",
        ) { h ->
            if (h == null) return@AnimatedContent
            Column(Modifier.padding(start = 16.u, bottom = 12.u)) {
                LogoTitle(h.name, h.logo, 128f)
                Spacer(Modifier.height(24.u))
                MetaLine(h)
                Spacer(Modifier.height(16.u))
                BasicText(
                    h.overview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 840.u).graphicsLayer { alpha = textA.value },
                    style = text(Type.SMALL, 400, Colors.Body).merge(TextStyle(lineHeight = 1.45.em, shadow = Shadow(Color(0xBF000000), blurRadius = 6f))),
                )
                Spacer(Modifier.height(36.u))
                if (buttons) Row(
                    // ModulateAlpha: no offscreen layer, so the fade does not crop the scaled pill.
                    Modifier.graphicsLayer { alpha = btnA.value; compositingStrategy = CompositingStrategy.ModulateAlpha },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.u),
                ) {
                    PillButton(Icons.Play, "Смотреть", focused)
                    if (slides > 1) Dots(slides, slide)
                }
            }
        }
    }
}

/** Text with an icon; only the focused button becomes a solid white pill, at once (only the size springs). */
@Composable
fun PillButton(icon: ImageVector, label: String, focused: Boolean) {
    val sc by animateFloatAsState(if (focused) FocusSpec.CONTROL else 1f, spring(0.5f, Spring.StiffnessMedium), label = "pb")
    val fg = if (focused) Colors.FocusFg else Colors.Fg
    Row(
        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
            .height(76.u).clip(Shapes.pill).background(if (focused) Colors.Focus else Color.Transparent)
            .padding(horizontal = 34.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.u),
    ) {
        Ico(icon, 30.u, fg)
        BasicText(label, style = text(Type.BODY, 600, fg).merge(TextStyle(shadow = if (focused) null else Shadow(Colors.Shade, blurRadius = 6f))))
    }
}

/** The carousel's dots, drawn in one pass: the long one slides along at draw time, nothing is laid out again. */
@Composable
private fun Dots(n: Int, on: Int) {
    val pos = animateFloatAsState(on.toFloat(), tween(400), label = "dot")
    Canvas(Modifier.padding(start = 26.u).size(((n - 1) * 20 + 34).u, 10.u)) {
        val unit = size.height / 10f
        var x = 0f
        for (i in 0 until n) {
            val k = (1f - kotlin.math.abs(i - pos.value)).coerceIn(0f, 1f)
            val w = (10f + 24f * k) * unit
            drawRoundRect(lerp(Color(0x59FFFFFF), Color.White, k), Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2))
            x += w + 10f * unit
        }
    }
}

/** Logos that are dark on dark: drawn white. The verdict is kept per URL, so a logo is sampled once. */
object LogoTone {
    val known = HashMap<String, Boolean>()
    val White = ColorFilter.tint(Color.White, BlendMode.SrcIn)

    fun isDark(url: String, bmp: Bitmap?): Boolean {
        known[url]?.let { return it }
        if (bmp == null || bmp.config == Bitmap.Config.HARDWARE) return false
        var sum = 0.0
        var n = 0
        val sx = (bmp.width / 32).coerceAtLeast(1)
        val sy = (bmp.height / 12).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24) > 128) {
                    sum += (0.2126 * ((p shr 16) and 255) + 0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)) / 255.0
                    n++
                }
                x += sx
            }
            y += sy
        }
        val dark = n > 8 && sum / n < 0.22
        known[url] = dark
        return dark
    }
}
