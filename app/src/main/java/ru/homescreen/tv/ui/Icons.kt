package ru.homescreen.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The mockup's 24x24 line icons. Colour comes from the Icon tint, so white here is only a placeholder. */
object Icons {
    private fun build(name: String, vararg paths: Pair<String, Boolean>): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for ((d, stroke) in paths) {
            val nodes = PathParser().parsePathString(d).toNodes()
            if (stroke) b.addPath(nodes, stroke = SolidColor(Color.White), strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            else b.addPath(nodes, fill = SolidColor(Color.White))
        }
        return b.build()
    }

    val Search = build("search", "M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z" to true, "M16 16l4.5 4.5" to true)
    val YouTube = build("yt", "M21.6 7.2a2.6 2.6 0 0 0-1.8-1.8C18.2 5 12 5 12 5s-6.2 0-7.8.4a2.6 2.6 0 0 0-1.8 1.8C2 8.8 2 12 2 12s0 3.2.4 4.8a2.6 2.6 0 0 0 1.8 1.8C5.8 19 12 19 12 19s6.2 0 7.8-.4a2.6 2.6 0 0 0 1.8-1.8c.4-1.6.4-4.8.4-4.8s0-3.2-.4-4.8zM10 15V9l5.2 3z" to false)
    val Inputs = build("inputs", "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" to true, "M8 21h8M12 17v4" to true)
    val Settings = build("settings",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z" to true,
        "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" to true)
    val Play = build("play", "M7 4.5v15l13-7.5z" to false)
    val Trailer = build("trailer", "M6 5h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z" to true, "M10 9l5 3-5 3z" to false)
    val Grid = build("grid", "M4.5 3h4a1.5 1.5 0 0 1 1.5 1.5v4A1.5 1.5 0 0 1 8.5 10h-4A1.5 1.5 0 0 1 3 8.5v-4A1.5 1.5 0 0 1 4.5 3zM15.5 3h4A1.5 1.5 0 0 1 21 4.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 14 8.5v-4A1.5 1.5 0 0 1 15.5 3zM4.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4A1.5 1.5 0 0 1 8.5 21h-4A1.5 1.5 0 0 1 3 19.5v-4A1.5 1.5 0 0 1 4.5 14zM15.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-4a1.5 1.5 0 0 1-1.5-1.5v-4a1.5 1.5 0 0 1 1.5-1.5z" to true)
    val Star = build("star", "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z" to false)
    val Moon = build("moon", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" to false)
    val Clock = build("clock", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z" to true, "M12 7.5V12l3 2" to true)
    val Doc = build("doc", "M7 3h7l4 4v14H7z" to true, "M10 12h6M10 16h6" to true)
    val Tv = build("tv", "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" to true, "M8 21h8" to true)
    val Family = build("family", "M8 5a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM16.5 7a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5zM2.5 20a5.5 5.5 0 0 1 11 0zM12.5 20a4.3 4.3 0 0 1 8.6 0z" to false)
    // The consoles' own marks (Simple Icons, CC0), for the button of the one plugged in.
    val PlayStation = build("playstation", "M8.984 2.596v17.547l3.915 1.261V6.688c0-.69.304-1.151.794-.991.636.18.76.814.76 1.505v5.875c2.441 1.193 4.362-.002 4.362-3.152 0-3.237-1.126-4.675-4.438-5.827-1.307-.448-3.728-1.186-5.39-1.502h-.003zm4.656 16.241l6.296-2.275c.715-.258.826-.625.246-.818-.586-.192-1.637-.139-2.357.123l-4.205 1.499v-2.385l.24-.085s1.201-.42 2.913-.615c1.696-.18 3.785.03 5.437.661 1.848.601 2.04 1.472 1.576 2.072-.465.6-1.622 1.036-1.622 1.036l-8.544 3.107V18.86l.02-.023zM1.807 18.6c-1.9-.545-2.214-1.668-1.352-2.32.801-.586 2.16-1.052 2.16-1.052l5.615-2.013v2.313L4.205 17c-.705.271-.825.632-.239.826.586.195 1.637.15 2.343-.12L8.247 17v2.074c-.12.03-.256.044-.39.073-1.939.331-3.996.196-6.038-.479z" to false)
    val Xbox = build("xbox", "M4.102 21.033C6.211 22.881 8.977 24 12 24c3.026 0 5.789-1.119 7.902-2.967 1.877-1.912-4.316-8.709-7.902-11.417-3.582 2.708-9.779 9.505-7.898 11.417zm11.16-14.406c2.5 2.961 7.484 10.313 6.076 12.912C23.002 17.48 24 14.861 24 12.004c0-3.34-1.365-6.362-3.57-8.536 0 0-.027-.022-.082-.042-.063-.022-.152-.045-.281-.045-.592 0-1.985.434-4.805 3.246zM3.654 3.426c-.057.02-.082.041-.086.042C1.365 5.642 0 8.664 0 12.004c0 2.854.998 5.473 2.661 7.533-1.401-2.605 3.579-9.951 6.08-12.91-2.82-2.813-4.216-3.245-4.806-3.245-.131 0-.223.021-.281.046v-.002zM12 3.551S9.055 1.828 6.755 1.746c-.903-.033-1.454.295-1.521.339C7.379.646 9.659 0 11.984 0H12c2.334 0 4.605.646 6.766 2.085-.068-.046-.615-.372-1.52-.339C14.946 1.828 12 3.545 12 3.545v.006z" to false)
    val Mic = build("mic", "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z" to true, "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21" to true)
    val Bookmark = build("bookmark", "M7 3.5h10a1 1 0 0 1 1 1V21l-6-4-6 4V4.5a1 1 0 0 1 1-1z" to true)
    val BookmarkOn = build("bookmark_on", "M7 3.5h10a1 1 0 0 1 1 1V21l-6-4-6 4V4.5a1 1 0 0 1 1-1z" to false)
    val Down = build("down", "M6 9.5l6 6 6-6" to true)
    val Subtitles = build("subtitles", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" to true, "M7 11.5h6M15 11.5h2M7 15h3M12 15h5" to true)
    val Refresh = build("refresh", "M21.35 5.2v5.1h-5.1" to true, "M19.22 14.55a7.65 7.65 0 1 1-1.8-7.96l3.93 3.71" to true)
    val Check = build("check", "M5 12.5l4.5 4.5L19 7.5" to true)
    val Close = build("close", "M6.5 6.5l11 11M17.5 6.5l-11 11" to true)
    val Left = build("left", "M14.5 6l-6 6 6 6" to true)
    val Right = build("right", "M9.5 6l6 6-6 6" to true)
    val Move = build("move", "M7.5 8l-4 4 4 4M16.5 8l4 4-4 4M3.5 12h17" to true)
    val Hide = build("hide", "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z" to true, "M4 4l16 16" to true)
    val Link = build("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" to true, "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" to true)
    val Pause = build("pause", "M6.5 5h3.5v14H6.5zM14 5h3.5v14H14z" to false)
    val Audio = build("audio", "M4 9.5h3.5L12 6v12l-4.5-3.5H4z" to true, "M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11" to true)
    val Episodes = build("episodes", "M9 6h11M9 12h11M9 18h11" to true, "M4.5 6h.01M4.5 12h.01M4.5 18h.01" to true)
    val Next = build("next", "M5 5.5v13l9.5-6.5z" to false, "M19 5v14" to true)
    val SkipAhead = build("skip_ahead", "M3.5 5.5v13l8-6.5zM12 5.5v13l8-6.5z" to false)
    val Undo = build("undo", "M4 5v6h6" to true, "M5.2 15a7.5 7.5 0 1 0 1.6-7.8L4 11" to true)
    val Antenna =build("antenna", "M5 9h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z" to true, "M8 3l4 5 4-5" to true)
}
