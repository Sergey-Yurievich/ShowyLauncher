package ru.homescreen.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentsTest {
    @Test
    fun readsIntroAndCredits() {
        val j = JSONObject("""{"tvdb_id":81189,"type":"tv","season":1,"episode":2,"intro":[{"start_ms":314568,"end_ms":330969}],"credits":[{"start_ms":2843000,"end_ms":null}]}""")
        val m = Segments.parse(j, 2_900_000)
        assertEquals(314_568L..330_969L, m.intro)
        assertEquals(2_843_000L, m.credits)
        assertNull(m.recap)
    }

    @Test
    fun dropsPlaceholdersAndStartsOpenIntrosAtZero() {
        val j = JSONObject("""{"recap":[{"start_ms":0,"end_ms":0}],"intro":[{"start_ms":null,"end_ms":119000}],"credits":[{"start_ms":0,"end_ms":0}]}""")
        val m = Segments.parse(j, 0)
        assertNull(m.recap)
        assertNull(m.credits)
        assertEquals(0L..119_000L, m.intro)
    }
}
