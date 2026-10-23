package ru.homescreen.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.homescreen.tv.data.Title
import ru.homescreen.tv.data.Tmdb

/**
 * Search by title, as in Lampa: TMDB films and series. Typed on an on-screen keyboard (or by voice), results come
 * half a second after the last key. With nothing typed the grid shows what is popular now.
 */
class SearchSession(
    private val scope: CoroutineScope,
    private val popular: () -> List<Title>,
    /** True / false: Showy has sources or not; null: could not tell. */
    private val hasSources: suspend (Title) -> Boolean?,
) {
    enum class Zone { KEYS, RESULTS }
    enum class Act { CHAR, SPACE, DELETE, LANG, VOICE }
    data class Key(val label: String, val span: Int = 1, val act: Act = Act.CHAR)

    var query by mutableStateOf("")
        private set
    var russian by mutableStateOf(true)
        private set
    var results by mutableStateOf<List<Title>>(emptyList())
        private set
    /** Results are for [query]; false while the grid shows the popular titles. */
    var searched by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    /** TMDB did not answer the last search. */
    var failed by mutableStateOf(false)
        private set

    var zone by mutableStateOf(Zone.KEYS)
    var row by mutableIntStateOf(1)
    /** Column in keyboard units (0..5): moving up and down keeps it even across wide keys. */
    var unit by mutableIntStateOf(0)
    var idx by mutableIntStateOf(0)

    private var job: Job? = null

    /** Showy sources per title key, filled in the background: the grid greys out what cannot be played. */
    val sources = mutableStateMapOf<String, Boolean>()
    private val checking = HashSet<String>()
    /** Checks that could not tell (no answer): not repeated until the app restarts. */
    private val unknown = HashSet<String>()

    val rows: List<List<Key>>
        get() = buildList {
            add(listOf(Key("Пробел", 2, Act.SPACE), Key("Стереть", 2, Act.DELETE), Key(if (russian) "EN" else "RU", 1, Act.LANG), Key("", 1, Act.VOICE)))
            val chars = (if (russian) "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" else "abcdefghijklmnopqrstuvwxyz") + "1234567890"
            chars.chunked(COLS).forEach { line -> add(line.map { Key(it.toString()) }) }
        }

    /** The key under the focus: the one in [row] that covers [unit]. */
    fun keyAt(r: Int = row, u: Int = unit): Pair<Int, Key>? {
        val line = rows.getOrNull(r) ?: return null
        var start = 0
        line.forEachIndexed { i, k ->
            if (u < start + k.span) return i to k
            start += k.span
        }
        return (line.size - 1) to line.last()
    }

    fun keyStart(r: Int, i: Int): Int = rows[r].take(i).sumOf { it.span }

    fun start() {
        if (results.isEmpty()) scope.launch { show() }
    }

    fun type(text: String) {
        query = text.take(60)
        job?.cancel()
        job = scope.launch {
            delay(450)
            // TMDB through the VPN sometimes drops a request: a few more tries before giving up.
            for (attempt in 0 until 3) {
                show()
                if (!failed) break
                delay(2500)
            }
        }
    }

    fun press(k: Key, voice: () -> Unit) {
        when (k.act) {
            Act.CHAR -> type(query + k.label)
            Act.SPACE -> if (query.isNotEmpty() && !query.endsWith(" ")) type("$query ")
            Act.DELETE -> if (query.isNotEmpty()) type(query.dropLast(1))
            Act.LANG -> {
                russian = !russian
                row = row.coerceAtMost(rows.lastIndex)
            }
            Act.VOICE -> voice()
        }
    }

    private suspend fun show() {
        val q = query.trim()
        if (q.length < 2) {
            searched = false
            failed = false
            results = popular()
            idx = idx.coerceIn(0, (results.size - 1).coerceAtLeast(0))
            pump()
            return
        }
        loading = true
        val found = Tmdb.search(q)
        loading = false
        if (q != query.trim()) return
        searched = true
        failed = found == null
        results = found.orEmpty()
        idx = 0
        pump()
        if (results.isEmpty() && zone == Zone.RESULTS) zone = Zone.KEYS
    }

    /** Call when the focus moves in the grid: the checks start from the focused title, like the plugin's. */
    fun focusMoved() = pump()

    /** Two checks at a time, the focused title and those after it first, then the ones before. */
    private fun pump() {
        while (checking.size < 2) {
            val order = results.drop(idx) + results.take(idx).asReversed()
            val next = order.firstOrNull { it.key !in sources && it.key !in checking && it.key !in unknown } ?: return
            checking += next.key
            scope.launch {
                try {
                    val ok = hasSources(next)
                    if (ok != null) sources[next.key] = ok else unknown += next.key
                } finally {
                    checking -= next.key
                    pump()
                }
            }
        }
    }

    companion object {
        const val COLS = 6
        const val RESULT_COLS = 5
    }
}
