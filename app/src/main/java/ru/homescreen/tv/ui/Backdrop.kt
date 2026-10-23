package ru.homescreen.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest

private val Ink = Color(0xFF050608)

/**
 * The full-screen backdrop. Nothing here moves on its own: an always-running animation would make the whole screen
 * redraw 60 times a second on a TV GPU. It changes only when the focus moves (crossfade, camera shift) and in trailer
 * mode (one slow zoom). Scrims and the film's colour are drawn in one pass and read their animations at draw time,
 * so they never recompose the screen.
 */
@Composable
fun Backdrop(url: String?, accent: State<Color>, rowsMode: Boolean, trailer: Boolean, shiftX: Float, shiftY: Float) {
    val tr = animateFloatAsState(if (trailer) 1f else 0f, tween(900), label = "tr")
    val rows = animateFloatAsState(if (rowsMode) 1f else 0f, tween(600), label = "rows")
    val sx = animateFloatAsState(shiftX, tween(1400, easing = FastOutSlowInEasing), label = "sx")
    val sy = animateFloatAsState(shiftY, tween(1400, easing = FastOutSlowInEasing), label = "sy")
    val zoom = remember { Animatable(1f) }
    LaunchedEffect(trailer) {
        if (trailer) zoom.animateTo(1.09f, tween(9000, easing = LinearOutSlowInEasing))
        else zoom.animateTo(1f, tween(700))
    }

    // Crossfade by hand: the new picture is loaded first, then faded in over the old one with ModulateAlpha. Compose's
    // Crossfade puts both full-screen images into offscreen layers while fading, which cost this GPU dropped frames
    // every time the focus paused during a fast scroll.
    val ctx = LocalContext.current
    var under by remember { mutableStateOf<String?>(null) }
    var over by remember { mutableStateOf<String?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(url) {
        val u = url ?: return@LaunchedEffect
        // A fade still running is finished quickly instead of dropped, so the picture never jumps.
        over?.let { o ->
            fade.animateTo(1f, tween(150))
            under = o; over = null; fade.snapTo(0f)
        }
        if (u == under) return@LaunchedEffect
        ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(u).build())
        if (under == null) { under = u; return@LaunchedEffect }
        over = u
        fade.animateTo(1f, tween(500))
        under = u; over = null; fade.snapTo(0f)
    }

    Box(Modifier.fillMaxSize()) {
        val camera = Modifier.fillMaxSize().graphicsLayer {
            val s = 1.04f * zoom.value
            scaleX = s; scaleY = s
            translationX = sx.value * LocalScaleHolder.px
            translationY = sy.value * LocalScaleHolder.px
        }
        under?.let { AsyncImage(it, null, camera, contentScale = ContentScale.Crop) }
        over?.let {
            AsyncImage(it, null, camera.graphicsLayer { alpha = fade.value; compositingStrategy = CompositingStrategy.ModulateAlpha },
                contentScale = ContentScale.Crop)
        }
        // Scrims are pre-rendered once into small bitmaps and only faded: full-screen multi-stop gradients drawn every
        // frame took ~35 ms of GPU per frame on this TV (20 fps); textures cost next to nothing.
        if ("scrim" !in DebugOff.set) {
            val light = remember { scrim(heavy = false) }
            val heavyImg = remember { scrim(heavy = true) }
            Image(light, null, Modifier.fillMaxSize().graphicsLayer { alpha = (1f - rows.value) * (1f - 0.5f * tr.value) }, contentScale = ContentScale.FillBounds)
            Image(heavyImg, null, Modifier.fillMaxSize().graphicsLayer { alpha = rows.value * (1f - 0.35f * tr.value) }, contentScale = ContentScale.FillBounds)
        }
    }
}

/** Left and bottom darkening baked into a 480x270 bitmap (smooth enough when stretched to the screen). */
private fun scrim(heavy: Boolean): ImageBitmap {
    val w = 480
    val h = 270
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val ink = 0x050608
    fun col(a: Float) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or ink
    val k = if (heavy) 1f else 0f
    val p = android.graphics.Paint()
    p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f,
        intArrayOf(col(0.82f + 0.08f * k), col(0.45f + 0.15f * k), col(0.08f * k), col(0f)),
        floatArrays(0f, 0.30f, 0.55f + 0.07f * k, 1f), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
        intArrayOf(col(0.55f), col(0f), col(0f), col(0.6f + 0.34f * k), col(0.97f)),
        floatArrays(0f, 0.2f, 0.58f - 0.18f * k, 0.78f - 0.16f * k, 1f), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    return bmp.asImageBitmap()
}

private fun floatArrays(vararg v: Float) = v

/** The pool of the film's colour under the focused tile. [cx]/[cy] in 1920-grid units. */
@Composable
fun Pool(cx: Float, cy: Float, visible: Boolean, accent: State<Color>) {
    val x = animateFloatAsState(cx, tween(300), label = "px")
    val y = animateFloatAsState(cy, tween(450), label = "py")
    val a = animateFloatAsState(if (visible) 1f else 0f, tween(300), label = "pa")
    Spacer(Modifier.fillMaxSize().drawBehind {
        if (a.value <= 0f) return@drawBehind
        val unit = LocalScaleHolder.px
        val c = Offset(x.value * unit, y.value * unit)
        val r = 300f * unit
        val col = accent.value
        drawOval(
            Brush.radialGradient(listOf(col.copy(alpha = 0.42f * a.value), col.copy(alpha = 0f)), c, r),
            topLeft = Offset(c.x - r, c.y - r * 0.38f),
            size = Size(r * 2, r * 0.76f),
        )
    })
}

object DebugOff { var set: Set<String> = emptySet() }
