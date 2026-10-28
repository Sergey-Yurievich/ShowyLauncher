package ru.homescreen.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchRangesTest {
    @Test
    fun mergesTouchingAndOverlappingRuns() {
        val r = Prefetch.Ranges()
        r.add(0, 10)
        r.add(20, 30)
        r.add(10, 15)
        r.add(25, 40)
        assertEquals(35L, r.total)
        assertEquals(15L, r.firstMissing(3))
        assertEquals(12L, r.readyAt(3))
        assertEquals(17L, r.firstMissing(17))
        assertEquals(0L, r.readyAt(17))
        r.add(15, 20)
        assertEquals(40L, r.firstMissing(0))
    }

    @Test
    fun removingAChunkSplitsARun() {
        val r = Prefetch.Ranges()
        r.add(0, 100)
        r.remove(32, 64)
        assertEquals(68L, r.total)
        assertEquals(32L, r.firstMissing(0))
        assertEquals(50L, r.firstMissing(50))
        assertEquals(36L, r.readyAt(64))
    }
}
