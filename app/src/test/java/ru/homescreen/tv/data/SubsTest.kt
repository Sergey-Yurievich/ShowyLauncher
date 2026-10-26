package ru.homescreen.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SubsTest {
    @Test
    fun forcedByShortLabel() {
        assertEquals(2, Subs.rank("Forc"))
        assertEquals("uk", Subs.language("Ukrainian"))
    }

    @Test
    fun decodesWindows1251AndDropsTheMark() {
        assertEquals("Привет", Subs.decode("Привет".toByteArray(charset("windows-1251"))))
        assertEquals("1\n", Subs.decode("\uFEFF1\n".toByteArray()))
    }
}
