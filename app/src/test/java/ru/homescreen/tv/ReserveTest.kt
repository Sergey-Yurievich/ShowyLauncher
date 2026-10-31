package ru.homescreen.tv

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.homescreen.tv.ui.reserve

class ReserveTest {
    private val min = 60_000L

    @Test fun toTheEnd() = assertEquals("Загружено до конца", reserve(100 * min, 10 * min, 100 * min))
    @Test fun underAMinute() = assertEquals("Запас меньше минуты", reserve(10 * min + 30_000, 10 * min, 100 * min))
    @Test fun minutes() = assertEquals("Запас 42 мин", reserve(52 * min, 10 * min, 100 * min))
    @Test fun hours() = assertEquals("Запас 1 ч 05 мин", reserve(75 * min, 10 * min, 200 * min))
    @Test fun behindThePlayer() = assertEquals("Запас меньше минуты", reserve(5 * min, 10 * min, 100 * min))
}
