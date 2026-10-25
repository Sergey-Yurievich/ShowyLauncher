package ru.homescreen.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.homescreen.tv.Tab
import ru.homescreen.tv.data.Title

enum class Zone { TOP, HERO, ROWS }
/** PERSON: an actor's films, over the film's screen they were opened from. */
enum class Layer { NONE, INPUTS, SETTINGS, GRID, WATCH, SEARCH, MENU, PERSON }

/** A line of the long-press menu. */
class MenuItem(val label: String, val icon: ImageVector?, val run: () -> Unit)

/** Top bar items, left to right: four tabs, search and bookmarks, then the round buttons (the console's only when one is plugged in). */
enum class TopItem { HOME, MOVIES, SERIES, APPS, SEARCH, BOOKMARKS, YOUTUBE, CONSOLE, INPUTS, SETTINGS }

/**
 * Where the remote's focus is. Kept as plain state instead of Compose focus: D-pad moves are then exact (Up/Down move
 * one row, each row remembers its column) and nothing can fall through to a tile behind an overlay.
 */
class Nav {
    var tab by mutableStateOf(Tab.HOME)
    var zone by mutableStateOf(Zone.HERO)
    var top by mutableStateOf(TopItem.HOME)
    var slide by mutableIntStateOf(0)
    var row by mutableIntStateOf(0)
    var col by mutableIntStateOf(0)
    val mem = HashMap<String, Int>()

    var layer by mutableStateOf(Layer.NONE)
    var inputIdx by mutableIntStateOf(0)
    var settingsIdx by mutableIntStateOf(0)
    var gridIdx by mutableIntStateOf(0)
    /** The layer the film's screen returns to on Back (the grid, search, or none). */
    var watchFrom = Layer.NONE

    /** The long-press menu, over the layer it was opened on ([menuFrom]). */
    var menu by mutableStateOf<List<MenuItem>>(emptyList())
    var menuIdx by mutableIntStateOf(0)
    var menuFrom = Layer.NONE
    /** What is on screen under the menu: the menu itself is only a panel over it. */
    val base: Layer get() = if (layer == Layer.MENU) menuFrom else layer
    /** The app being moved to a new place (package), after «Переместить». */
    var moving by mutableStateOf<String?>(null)
    /** OK is down and not yet a long press ([okLong]); it acts when released. */
    var okDown = false
    var okLong = false

    var trailer by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    /** Spring "kick" of the focused tile toward the direction of the move, in degrees. */
    val kickX = Animatable(0f)
    val kickY = Animatable(0f)

    fun kick(scope: CoroutineScope, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        scope.launch {
            kickY.snapTo(dx * 9f)
            kickY.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow))
        }
        scope.launch {
            kickX.snapTo(-dy * 7f)
            kickX.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow))
        }
    }

    /** Opens the menu over the current layer; false (nothing opened) when there is nothing to offer. */
    fun openMenu(items: List<MenuItem>): Boolean {
        if (items.isEmpty()) return false
        menu = items
        menuIdx = 0
        menuFrom = layer
        layer = Layer.MENU
        return true
    }

    fun topIndexOf(tab: Tab): TopItem = TopItem.entries[tab.ordinal]

    fun switchTab(t: Tab) {
        if (tab == t) return
        tab = t; slide = 0; row = 0; col = 0; mem.clear()
    }
}
