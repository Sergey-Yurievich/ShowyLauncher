package ru.homescreen.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeNameTest {
    @Test
    fun torrentFileNamesBecomeEpisodeNumbers() {
        assertEquals("5 серия", WatchSession.episodeName("Slow.Horses.S01E05.HDR.Master5.mkv", 5))
        assertEquals("5 серия", WatchSession.episodeName("Slow Horses s01e05 [LostFilm]", 5))
        assertEquals("Фиаско", WatchSession.episodeName("Фиаско", 5))
        assertEquals("Extras.mkv", WatchSession.episodeName("Extras.mkv", 0))
    }
}
