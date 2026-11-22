package ru.homescreen.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressOrderTest {
    @Test
    fun finishedFirstThenUntriedThenStalled() {
        val verdict = mapOf("stalled" to false, "fine" to true)
        assertEquals(listOf("fine", "new", "stalled"), byVerdict(listOf("stalled", "new", "fine"), verdict::get))
    }

    @Test
    fun orderWithinAGroupIsKept() {
        val verdict = mapOf("a" to true, "b" to false, "c" to true, "d" to false)
        assertEquals(listOf("a", "c", "b", "d"), byVerdict(listOf("a", "b", "c", "d"), verdict::get))
    }

    @Test
    fun noneFinishedKeepsTheOrderGiven() {
        assertEquals(listOf("x", "y"), byVerdict(listOf("x", "y")) { false })
        assertEquals(listOf("x", "y"), byVerdict(listOf("x", "y")) { null })
    }
}
