package ru.homescreen.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicJsonFileTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun writesAndReadsBack() {
        val f = File(dir.root, "a.json")
        AtomicJsonFile(f).writeNow("""{"k":1}""")
        assertEquals(1, AtomicJsonFile(f).readObject().getInt("k"))
        assertFalse(File(dir.root, "a.json.tmp").exists())
    }

    @Test fun missingFileIsEmpty() {
        val f = AtomicJsonFile(File(dir.root, "none.json"))
        assertEquals(0, f.readObject().length())
        assertNull(f.read { it })
    }

    @Test fun brokenFileIsSetAsideNotOverwritten() {
        val f = File(dir.root, "b.json").apply { writeText("{\"k\":") }
        assertEquals(0, AtomicJsonFile(f).readObject().length())
        assertFalse(f.exists())
        assertEquals("{\"k\":", File(dir.root, "b.json.corrupt").readText())
    }

    @Test fun backgroundWriteKeepsTheLatest() {
        val f = File(dir.root, "c.json")
        val j = AtomicJsonFile(f)
        repeat(50) { j.write("""{"n":$it}""") }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && (!f.exists() || AtomicJsonFile(f).readObject().optInt("n") != 49)) Thread.sleep(10)
        assertTrue(f.exists())
        assertEquals(49, AtomicJsonFile(f).readObject().getInt("n"))
    }
}
