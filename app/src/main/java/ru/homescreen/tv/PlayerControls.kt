package ru.homescreen.tv

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.homescreen.tv.data.PlayerPrefs
import ru.homescreen.tv.data.Segments

/**
 * The built-in player's screen and remote: what is drawn over the video, where the focus is, the intro and next-episode
 * prompts. [PlayerActivity] owns the ExoPlayer, feeds [tick] four times a second and hands the keys here; the
 * composables in ui/PlayerScreen.kt only read.
 *
 * Without anything on screen OK pauses and opens the panel, ← → seek by 30 s, ↑ ↓ open the panel without pausing and
 * Back leaves. While a skip or next-episode button shows, OK presses it.
 */
class PlayerControls(private val host: Host, val playback: Playback) {
    interface Host {
        val position: Long
        val duration: Long
        val buffered: Long
        val playing: Boolean
        fun seekTo(ms: Long)
        fun setPlaying(on: Boolean)
        fun chooseAudio(i: Int)
        /** [line] 0 is the upper (English) one; [key] a [Track.key], or [OFF]. */
        fun chooseText(line: Int, key: String)
        fun playEpisode(i: Int)
        fun exit()
    }

    enum class Mode { NONE, PANEL, SEEK, AUDIO, SUBS, EPISODES }
    enum class Button { PLAY, AUDIO, SUBS, EPISODES, NEXT }
    enum class Kind { INTRO, RECAP }

    /** [lang]: two letters for subtitle tracks, kept per title when picked. */
    data class Track(val key: String, val title: String, val sub: String, val lang: String? = null)

    /** A skip button over an intro or a recap; [auto] when it was already skipped and the button goes back. */
    data class Skip(val kind: Kind, val range: LongRange, val auto: Boolean)

    var mode by mutableStateOf(Mode.NONE)
        private set
    var button by mutableIntStateOf(0)
        private set
    /** The panel's focus is on the progress bar rather than the buttons. */
    var onBar by mutableStateOf(false)
        private set

    var index by mutableIntStateOf(playback.start)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var buffered by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    /** An episode's link is being asked for, or the stream is filling up. */
    var loading by mutableStateOf(true)

    /** Where ← → have got to; applied a moment after the last press. */
    var seekTarget by mutableStateOf<Long?>(null)
        private set
    var seekFrom by mutableLongStateOf(0L)
        private set
    private var seekApplyAt = 0L
    private var lastStep = 0L

    /** The two subtitle lines as they show now. */
    var upper by mutableStateOf("")
    var lower by mutableStateOf("")

    var audio by mutableStateOf<List<Track>>(emptyList())
    var audioSelected by mutableIntStateOf(-1)
    var texts by mutableStateOf<List<Track>>(emptyList())
    /** The keys of the tracks on the upper and lower line, [OFF] for none. */
    var textUpper by mutableStateOf(OFF)
    var textLower by mutableStateOf(OFF)
    var badges by mutableStateOf<List<String>>(emptyList())

    var audioIdx by mutableIntStateOf(0)
        private set
    var subsCol by mutableIntStateOf(0)
        private set
    var subsRow by mutableIntStateOf(0)
        private set
    var onSize by mutableStateOf(false)
        private set
    var epOnTabs by mutableStateOf(false)
        private set
    var epSeason by mutableIntStateOf(0)
        private set
    var epIdx by mutableIntStateOf(0)
        private set

    var segments by mutableStateOf<Segments.Marks?>(null)
    var skip by mutableStateOf<Skip?>(null)
        private set
    /** When the skip button last went away: an OK meant for it that comes a moment late does not pause. */
    private var skipGoneAt = 0L
    private var autoUntil = 0L
    private val autoDone = HashSet<Kind>()
    private val declined = HashSet<Kind>()

    /** The next-episode card: shown, its countdown 0..1 and the focused button (0 «Смотреть», 1 «Досмотреть титры»). */
    var nextShown by mutableStateOf(false)
        private set
    var nextFill by mutableFloatStateOf(0f)
        private set
    var nextLeft by mutableIntStateOf(0)
        private set
    var nextFocus by mutableIntStateOf(0)
        private set
    private var nextLeftMs = 0L
    private var nextDismissed = false

    var toast by mutableStateOf<String?>(null)
        private set
    private var toastUntil = 0L
    private var hideAt = 0L
    /** When the panel hid by itself: a Back meant for it that comes a moment late does not leave. */
    private var hiddenAt = 0L
    private var lastTick = 0L

    val item: Playback.Item get() = playback.items[index]
    val hasNext: Boolean get() = playback.serial && index + 1 < playback.items.size
    val seasons: List<Int> get() = playback.items.map { it.season }.distinct()

    val buttons: List<Button>
        get() = buildList {
            add(Button.PLAY)
            if (audio.size > 1) add(Button.AUDIO)
            add(Button.SUBS)
            if (playback.serial && playback.items.size > 1) add(Button.EPISODES)
            if (hasNext) add(Button.NEXT)
        }

    /** The episodes of the season [epSeason] points at, with their indices in [Playback.items]. */
    fun seasonItems(): List<Pair<Int, Playback.Item>> {
        val s = seasons.getOrNull(epSeason)
        return playback.items.withIndex().filter { it.value.season == s }.map { it.index to it.value }
    }

    /** Where the episode counts as watched: the end credits, or a minute before the end. */
    val watchedAt: Long get() = segments?.credits?.takeIf { it < duration } ?: (duration - 60_000)

    /** A new episode (or the first one) is loading: what belonged to the old one goes. */
    fun startItem(i: Int) {
        index = i
        segments = null
        skip = null
        autoDone.clear(); declined.clear()
        nextShown = false; nextDismissed = false
        seekTarget = null
        upper = ""; lower = ""
        if (mode != Mode.NONE) mode = Mode.NONE
    }

    fun say(text: String, now: Long) {
        toast = text
        toastUntil = now + 3500
    }

    // ---------- Time ----------

    fun tick(now: Long) {
        val dt = if (lastTick == 0L) 0L else now - lastTick
        lastTick = now
        position = host.position
        duration = host.duration
        buffered = host.buffered
        playing = host.playing
        if (seekTarget != null && now >= seekApplyAt) applySeek(now)
        if ((mode == Mode.PANEL && playing) || mode == Mode.SEEK) if (now >= hideAt && seekTarget == null) {
            mode = Mode.NONE
            hiddenAt = now
        }
        if (toast != null && now >= toastUntil) toast = null
        updateSkip(now)
        updateNext(dt)
    }

    private fun updateSkip(now: Long) {
        val was = skip
        findSkip(now)
        if (was != null && skip == null) skipGoneAt = now
    }

    private fun findSkip(now: Long) {
        val m = segments
        if (skip?.auto == true && now < autoUntil) return
        if (m == null || seekTarget != null) { skip = null; return }
        val pos = position
        for ((kind, range) in listOf(Kind.RECAP to m.recap, Kind.INTRO to m.intro)) {
            if (range == null || pos !in range.first until range.last - 1500) continue
            if (PlayerPrefs.skipIntros && kind !in declined && kind !in autoDone) {
                autoDone += kind
                host.seekTo(range.last)
                skip = Skip(kind, range, auto = true)
                autoUntil = now + 5000
                return
            }
            skip = Skip(kind, range, auto = false)
            return
        }
        skip = null
    }

    private fun updateNext(dt: Long) {
        if (!hasNext || duration < 300_000) { nextShown = false; return }
        val at = segments?.credits?.takeIf { it < duration } ?: (duration - 30_000)
        val pos = seekTarget ?: position
        // Back before the credits, and the card may come again.
        if (pos < at - 5_000) nextDismissed = false
        if (pos < at || nextDismissed) { nextShown = false; return }
        if (!nextShown) {
            nextShown = true
            nextFocus = 0
            nextLeftMs = COUNTDOWN
        } else if (playing && mode == Mode.NONE) {
            nextLeftMs -= dt
        }
        nextFill = 1f - nextLeftMs.coerceAtLeast(0) / COUNTDOWN.toFloat()
        nextLeft = ((nextLeftMs + 999) / 1000).toInt().coerceAtLeast(0)
        if (nextLeftMs <= 0) {
            nextShown = false
            host.playEpisode(index + 1)
        }
    }

    // ---------- Keys ----------

    /** A key going down; true when it was ours. */
    fun key(code: Int, repeat: Int, now: Long): Boolean {
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (ok && repeat > 0) return true
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { toggle(now); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { host.setPlaying(true); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { host.setPlaying(false); openPanel(now); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { step(+1, repeat, now); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { step(-1, repeat, now); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (hasNext) host.playEpisode(index + 1); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { host.exit(); return true }
        }
        val back = code == KeyEvent.KEYCODE_BACK
        val left = code == KeyEvent.KEYCODE_DPAD_LEFT
        val right = code == KeyEvent.KEYCODE_DPAD_RIGHT
        val up = code == KeyEvent.KEYCODE_DPAD_UP
        val down = code == KeyEvent.KEYCODE_DPAD_DOWN
        if (!(ok || back || left || right || up || down)) return false
        hideAt = now + HIDE
        when (mode) {
            Mode.NONE -> when {
                nextShown && (ok || left || right || back) -> when {
                    back -> dismissNext()
                    left || right -> nextFocus = 1 - nextFocus
                    nextFocus == 0 -> { nextShown = false; host.playEpisode(index + 1) }
                    else -> dismissNext()
                }
                skip != null && ok -> pressSkip()
                ok && skipGoneAt != 0L && now - skipGoneAt < 1500 -> {}
                skip?.auto == true && back -> skip = null
                ok -> { host.setPlaying(false); openPanel(now) }
                left -> step(-1, repeat, now)
                right -> step(+1, repeat, now)
                up || down -> openPanel(now)
                back && hiddenAt != 0L && now - hiddenAt < 1500 -> {}
                back -> host.exit()
            }
            Mode.SEEK -> when {
                left -> step(-1, repeat, now)
                right -> step(+1, repeat, now)
                ok -> { applySeek(now); mode = Mode.NONE }
                back -> { seekTarget = null; mode = Mode.NONE }
                up || down -> { if (seekTarget != null) applySeek(now); openPanel(now, bar = true) }
            }
            Mode.PANEL -> if (onBar) when {
                left -> step(-1, repeat, now)
                right -> step(+1, repeat, now)
                ok -> applySeek(now)
                down -> { if (seekTarget != null) applySeek(now); onBar = false }
                back -> if (seekTarget != null) seekTarget = null else mode = Mode.NONE
            } else when {
                left -> button = (button - 1).coerceAtLeast(0)
                right -> button = (button + 1).coerceAtMost(buttons.lastIndex)
                up -> onBar = true
                ok -> press(buttons.getOrNull(button) ?: Button.PLAY, now)
                back -> mode = Mode.NONE
            }
            Mode.AUDIO -> when {
                up -> audioIdx = (audioIdx - 1).coerceAtLeast(0)
                down -> audioIdx = (audioIdx + 1).coerceAtMost(audio.lastIndex)
                ok -> { host.chooseAudio(audioIdx); mode = Mode.PANEL }
                back -> mode = Mode.PANEL
            }
            Mode.SUBS -> subsKey(ok, back, left, right, up, down)
            Mode.EPISODES -> episodesKey(ok, back, left, right, up, down)
        }
        return true
    }

    private fun toggle(now: Long) {
        if (playing) { host.setPlaying(false); openPanel(now) } else { host.setPlaying(true); hideAt = now + 2500 }
    }

    private fun openPanel(now: Long, bar: Boolean = false) {
        mode = Mode.PANEL
        onBar = bar
        button = 0
        hideAt = now + HIDE
    }

    private fun press(b: Button, now: Long) {
        when (b) {
            Button.PLAY -> toggle(now)
            Button.AUDIO -> { audioIdx = audioSelected.coerceAtLeast(0); mode = Mode.AUDIO }
            Button.SUBS -> {
                subsCol = 0
                subsRow = texts.indexOfFirst { it.key == textUpper }.let { if (it < 0) texts.size else it }
                onSize = false
                mode = Mode.SUBS
            }
            Button.EPISODES -> {
                epSeason = seasons.indexOf(item.season).coerceAtLeast(0)
                epIdx = seasonItems().indexOfFirst { it.first == index }.coerceAtLeast(0)
                epOnTabs = false
                mode = Mode.EPISODES
            }
            Button.NEXT -> host.playEpisode(index + 1)
        }
    }

    /** Two columns of the same tracks (the last row «Выключить») and the size under them. */
    private fun subsKey(ok: Boolean, back: Boolean, left: Boolean, right: Boolean, up: Boolean, down: Boolean) {
        val rows = texts.size + 1
        if (onSize) when {
            left -> PlayerPrefs.setSize(PlayerPrefs.subsSize - 1)
            right -> PlayerPrefs.setSize(PlayerPrefs.subsSize + 1)
            up -> onSize = false
            back -> mode = Mode.PANEL
        } else when {
            up -> subsRow = (subsRow - 1).coerceAtLeast(0)
            down -> if (subsRow >= rows - 1) onSize = true else subsRow++
            left -> subsCol = 0
            right -> subsCol = 1
            ok -> host.chooseText(subsCol, texts.getOrNull(subsRow)?.key ?: OFF)
            back -> mode = Mode.PANEL
        }
    }

    private fun episodesKey(ok: Boolean, back: Boolean, left: Boolean, right: Boolean, up: Boolean, down: Boolean) {
        val list = seasonItems()
        if (epOnTabs) when {
            left -> { epSeason = (epSeason - 1).coerceAtLeast(0); epIdx = 0 }
            right -> { epSeason = (epSeason + 1).coerceAtMost(seasons.lastIndex); epIdx = 0 }
            down || ok -> epOnTabs = false
            back -> mode = Mode.PANEL
        } else when {
            left -> epIdx = (epIdx - 1).coerceAtLeast(0)
            right -> epIdx = (epIdx + 1).coerceAtMost(list.lastIndex)
            up -> if (seasons.size > 1) epOnTabs = true
            ok -> list.getOrNull(epIdx)?.let { (i, _) ->
                mode = Mode.NONE
                if (i != index) host.playEpisode(i)
            }
            back -> mode = Mode.PANEL
        }
    }

    private fun pressSkip() {
        val s = skip ?: return
        skip = null
        skipGoneAt = 0
        if (s.auto) {
            declined += s.kind
            host.seekTo(s.range.first)
        } else {
            host.seekTo(s.range.last)
        }
    }

    private fun dismissNext() {
        nextShown = false
        nextDismissed = true
    }

    /**
     * ← → move the target by 30 s; held down, a step every quarter second, a minute each after three seconds.
     * Nothing outside the panel but the bar shows meanwhile.
     */
    private fun step(dir: Int, repeat: Int, now: Long) {
        if (repeat > 0 && now - lastStep < 240) return
        lastStep = now
        val d = duration.takeIf { it > 0 } ?: return
        if (seekTarget == null) seekFrom = position
        val size = if (repeat > 12) 60_000L else STEP
        seekTarget = ((seekTarget ?: position) + dir * size).coerceIn(0, (d - 3_000).coerceAtLeast(0))
        seekApplyAt = now + 800
        hideAt = now + HIDE
        if (mode == Mode.NONE) mode = Mode.SEEK
    }

    private fun applySeek(now: Long) {
        val t = seekTarget ?: return
        seekTarget = null
        host.seekTo(t)
        position = t
        hideAt = now + 1500
    }

    companion object {
        const val OFF = "off"
        private const val STEP = 30_000L
        private const val HIDE = 5_000L
        private const val COUNTDOWN = 10_000L
    }
}
