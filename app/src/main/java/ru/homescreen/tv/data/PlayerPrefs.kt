package ru.homescreen.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The built-in player's settings: skipping intros by itself and the subtitle size for every title, the subtitle
 * languages for each title.
 */
object PlayerPrefs {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("homescreen", Context.MODE_PRIVATE)
        skipIntros = prefs.getBoolean("skip_intros", false)
        subsSize = prefs.getInt("subs_size", 1).coerceIn(SIZES.indices)
    }

    /** Intros and recaps are skipped without asking, with «Вернуться» for a few seconds (the gear menu's «Плеер»). */
    var skipIntros by mutableStateOf(false)
        private set

    fun toggleSkip() {
        skipIntros = !skipIntros
        prefs.edit().putBoolean("skip_intros", skipIntros).apply()
    }

    /** An index into [SIZES]. */
    var subsSize by mutableIntStateOf(1)
        private set

    fun setSize(i: Int) {
        subsSize = i.coerceIn(SIZES.indices)
        prefs.edit().putInt("subs_size", subsSize).apply()
    }

    /**
     * The subtitle languages last picked for a title in the player, upper line and lower ("en", "ru"; "" for off). A
     * title never set has none: subtitles start off.
     */
    fun subtitles(key: String): Pair<String, String> =
        prefs.getString("subs_$key", null)?.split('|')?.let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } } ?: ("" to "")

    fun setSubtitles(key: String, upper: String, lower: String) {
        prefs.edit().putString("subs_$key", "$upper|$lower").apply()
    }

    /** Subtitle text heights on the 1920x1080 grid, with their names in the menu. */
    val SIZES = listOf(38 to "Маленький", 46 to "Средний", 56 to "Крупный")
}
