package ru.homescreen.tv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.homescreen.tv.data.Segments

class PlayerControlsTest {
    private class FakeHost : PlayerControls.Host {
        override var position = 600_000L
        override var duration = 2_400_000L
        override val buffered get() = position
        var on = true
        override val playing get() = on
        val seeks = ArrayList<Long>()
        val episodes = ArrayList<Int>()
        var exited = false
        override fun seekTo(ms: Long) { seeks += ms; position = ms }
        override fun setPlaying(on: Boolean) { this.on = on }
        override fun chooseAudio(i: Int) {}
        override fun chooseText(line: Int, key: String) {}
        override fun playEpisode(i: Int) { episodes += i }
        override fun exit() { exited = true }
    }

    private fun session(host: FakeHost) = PlayerControls(
        host,
        Playback(
            "tv_1", "Сериал", 1, true, List(3) { Playback.Item(1, it + 1, null, null, 40) }, 0,
            open = { null }, report = { _, _, _, _ -> }, progress = { 0f },
        ),
    ).also { it.tick(1) }

    @Test
    fun rightSeeksByThirtySecondsOnceThePressesStop() {
        val h = FakeHost()
        val c = session(h)
        c.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1_000)
        c.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1_300)
        c.key(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1_600)
        assertEquals(PlayerControls.Mode.SEEK, c.mode)
        assertEquals(690_000L, c.seekTarget)
        c.tick(2_000)
        assertTrue(h.seeks.isEmpty())
        c.tick(2_500)
        assertEquals(listOf(690_000L), h.seeks)
        assertNull(c.seekTarget)
    }

    @Test
    fun introButtonSkipsToItsEnd() {
        val h = FakeHost().apply { position = 65_000 }
        val c = session(h)
        c.segments = Segments.Marks(60_000L..120_000L, null, null)
        c.tick(1_000)
        assertEquals(PlayerControls.Kind.INTRO, c.skip?.kind)
        c.key(KeyEvent.KEYCODE_DPAD_CENTER, 0, 1_100)
        assertEquals(listOf(120_000L), h.seeks)
        assertEquals(PlayerControls.Mode.NONE, c.mode)
    }

    @Test
    fun nextEpisodeStartsWhenTheCountdownEnds() {
        val h = FakeHost().apply { position = 2_380_000 }
        val c = session(h)
        var now = 1_000L
        c.tick(now)
        assertTrue(c.nextShown)
        repeat(41) { now += 250; c.tick(now) }
        assertEquals(listOf(1), h.episodes)
    }

    @Test
    fun okPausesAndBackLeaves() {
        val h = FakeHost()
        val c = session(h)
        c.key(KeyEvent.KEYCODE_DPAD_CENTER, 0, 1_000)
        assertEquals(PlayerControls.Mode.PANEL, c.mode)
        assertEquals(false, h.on)
        c.key(KeyEvent.KEYCODE_BACK, 0, 1_100)
        assertEquals(PlayerControls.Mode.NONE, c.mode)
        c.key(KeyEvent.KEYCODE_BACK, 0, 1_200)
        assertTrue(h.exited)
    }
}
