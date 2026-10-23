package ru.homescreen.tv

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.homescreen.tv.data.Details
import ru.homescreen.tv.data.Episode
import ru.homescreen.tv.data.History
import ru.homescreen.tv.data.Media
import ru.homescreen.tv.data.Showy
import ru.homescreen.tv.data.Title
import ru.homescreen.tv.data.Tmdb
import ru.homescreen.tv.data.Vimu

/**
 * «Смотреть» without Lampa: the title's Showy sources, then season and voice, then the files; OK plays in ViMu.
 * The picked source / season / voice and the positions ViMu reports are kept in [History].
 */
class WatchSession(
    val title: Title,
    private val scope: CoroutineScope,
    private val history: History,
    private val detailsOf: suspend (Title) -> Details?,
    /** The details as loaded so far (for the trailer pill); reading it follows them as they arrive. */
    private val details: () -> Details?,
    private val onPlayed: () -> Unit,
) {
    enum class Phase { LOADING, AUTH, DENIED, READY, ERROR }
    enum class Chip { SOURCE, SEASON, VOICE, TRAILER, BOOKMARK }
    enum class Zone { CHIPS, LIST }

    var phase by mutableStateOf(Phase.LOADING)
        private set
    var message by mutableStateOf("")
        private set
    var code by mutableIntStateOf(0)
        private set

    var sources by mutableStateOf<List<Showy.Source>>(emptyList())
        private set
    var sourcesDone by mutableStateOf(false)
        private set
    var source by mutableStateOf<Showy.Source?>(null)
        private set
    var seasons by mutableStateOf<List<Showy.Item>>(emptyList())
        private set
    var season by mutableIntStateOf(-1)
        private set
    var voices by mutableStateOf<List<Showy.Item>>(emptyList())
        private set
    var voice by mutableIntStateOf(-1)
        private set
    var files by mutableStateOf<List<Showy.Item>>(emptyList())
        private set
    var similar by mutableStateOf<List<Showy.Item>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    /** Why the list is empty, or what went wrong with the last OK. */
    var note by mutableStateOf("")
        private set

    var zone by mutableStateOf(Zone.LIST)
    var chip by mutableIntStateOf(0)
    var idx by mutableIntStateOf(0)
    var picker by mutableStateOf<Chip?>(null)
    var pickIdx by mutableIntStateOf(0)

    /** TMDB episodes by season number, for names, length and rating. */
    val episodes = mutableStateMapOf<Int, Map<Int, Episode>>()
    /** Quality of `call` files, known only after asking the server; by file URL. */
    val qualities = mutableStateMapOf<String, String>()
    private val seasonsAsked = HashSet<Int>()
    private var probeJob: Job? = null

    /** Bumped on every result from ViMu, so the list redraws its progress bars. */
    var marks by mutableIntStateOf(0)
        private set

    private val serial = title.media == Media.TV
    private var query: Showy.Query? = null
    private var choice = history.choice(title.key) ?: History.Choice("", "")
    private val jobs = ArrayList<Job>()
    private var pageJob: Job? = null
    /** The source was picked for the user, not by them: an empty one gives way to the next. */
    private var auto = false

    val chips: List<Chip>
        get() = buildList {
            if (sources.isNotEmpty()) add(Chip.SOURCE)
            if (seasons.isNotEmpty()) add(Chip.SEASON)
            if (voices.isNotEmpty()) add(Chip.VOICE)
            if (trailer() != null) add(Chip.TRAILER)
            add(Chip.BOOKMARK)
        }

    fun trailer(): String? = details()?.trailer

    /** What the list shows: the files, or the titles to choose from when the source was not sure. */
    val list: List<Showy.Item> get() = if (files.isEmpty()) similar else files

    fun start() {
        jobs += scope.launch { if (Showy.token.isEmpty()) auth() else load() }
    }

    fun close() {
        jobs.forEach { it.cancel() }
        pageJob?.cancel()
        probeJob?.cancel()
    }

    fun retry() {
        if (phase == Phase.ERROR || phase == Phase.DENIED) {
            close()
            sources = emptyList(); source = null; sourcesDone = false
            start()
        }
    }

    // ---------- Loading ----------

    private suspend fun auth() {
        phase = Phase.AUTH
        code = Showy.newCode() ?: run { fail("Сервер Showy не отвечает"); return }
        repeat(200) {
            delay(3000)
            when (Showy.checkCode(code)) {
                Showy.CodeState.OK -> { load(); return }
                Showy.CodeState.EXPIRED -> { phase = Phase.DENIED; message = "PRO-подписка Showy закончилась. Продлить её можно в @showybot."; return }
                Showy.CodeState.PENDING -> {}
            }
        }
        fail("Код устарел. Нажмите OK, чтобы получить новый.")
    }

    private suspend fun load() {
        phase = Phase.LOADING
        val d = detailsOf(title)
        val q = Showy.externalIds(Showy.query(title, d))
        query = q
        try {
            Showy.sources(q) { list, done ->
                sources = list
                sourcesDone = done
                if (list.isNotEmpty()) phase = Phase.READY
                // Filmix first, always; an empty one gives way to the next source (see [auto]).
                if (source == null) {
                    val pick = list.firstOrNull { it.key == DEFAULT_SOURCE && it.show }
                        ?: if (done) list.firstOrNull { it.show } ?: list.firstOrNull() else null
                    pick?.let { selectSource(it, auto = true) }
                }
            }
            if (sources.isEmpty()) fail("Источники не нашли этот фильм")
        } catch (e: Showy.Denied) {
            denied(e.msg)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            fail("Нет связи с Showy")
        }
    }

    private suspend fun denied(msg: String) {
        if (msg.contains("авториз", ignoreCase = true)) {
            Showy.token = ""
            auth()
        } else {
            phase = Phase.DENIED
            message = msg
        }
    }

    private fun fail(msg: String) {
        phase = Phase.ERROR
        message = msg
    }

    fun selectSource(s: Showy.Source, auto: Boolean = false) {
        this.auto = auto
        source = s
        seasons = emptyList(); season = -1
        voices = emptyList(); voice = -1
        files = emptyList(); similar = emptyList()
        val q = query ?: return
        open(s.url + (if ('?' in s.url) "&" else "?") + Showy.params(q))
    }

    fun selectSeason(i: Int) {
        val s = seasons.getOrNull(i) ?: return
        season = i
        choice = choice.copy(season = s.text)
        files = emptyList()
        open(s.url)
    }

    fun selectVoice(i: Int) {
        val v = voices.getOrNull(i) ?: return
        voice = i
        choice = choice.copy(voice = v.text)
        files = emptyList()
        open(v.url)
    }

    fun selectSimilar(item: Showy.Item) {
        val from = similar
        similar = emptyList()
        // A series' torrent: the first time its server may need a while for the file list; none, and the list is back.
        if (item.sizeGb != null) open(item.url, 45, from to from.indexOf(item)) else open(item.url)
    }

    /**
     * Loads a page and follows it down to files: the remembered (or first) season, then the remembered voice. [back]
     * is the list to return to (and the row to focus) when the page brings nothing.
     */
    private fun open(url: String, timeoutSec: Long = 15, back: Pair<List<Showy.Item>, Int>? = null) {
        pageJob?.cancel()
        pageJob = scope.launch {
            busy = true
            note = ""
            val answered = try {
                var page = Showy.page(url, timeoutSec)
                var followedVoice = false
                for (step in 0 until 4) {
                    if (page.files.isEmpty() && page.similar.isNotEmpty()) {
                        // One search result from the title's year is the title: open it instead of asking.
                        val same = page.sameYear(title.year)
                        if (same.size == 1) {
                            page = Showy.page(same[0].url)
                            continue
                        }
                        // A series' torrents (ShowyTor) come as a list to pick from: biggest first, as a film's. Search
                        // results have no size and keep the source's order.
                        similar = page.similar.let { l -> if (l.any { it.sizeGb != null }) l.sortedByDescending { it.sizeGb ?: 0.0 } else l }
                        break
                    }
                    if (page.files.isEmpty() && page.seasons.isNotEmpty()) {
                        seasons = page.seasons
                        if (page.voices.isNotEmpty()) takeVoices(page.voices)
                        val i = seasons.indexOfFirst { it.text == choice.season }.takeIf { it >= 0 } ?: 0
                        season = i
                        page = Showy.page(seasons[i].url)
                        continue
                    }
                    if (page.voices.isNotEmpty()) {
                        takeVoices(page.voices)
                        val want = voices.indexOfFirst { it.text == choice.voice }
                        if (!followedVoice && want >= 0 && !voices[want].active) {
                            followedVoice = true
                            voice = want
                            page = Showy.page(voices[want].url)
                            continue
                        }
                    }
                    files = arrange(page.files)
                    break
                }
                files.firstOrNull()?.let { requestSeason(seasonOf(it)) }
                true
            } catch (e: Showy.Denied) {
                busy = false
                denied(e.msg)
                return@launch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            } finally {
                busy = false
            }
            if (list.isEmpty() && back != null) {
                similar = back.first
                idx = back.second.coerceAtLeast(0)
                note = "Раздача не открылась. Попробуйте ещё раз или выберите другую."
                return@launch
            }
            if (list.isEmpty() && auto) {
                val i = sources.indexOf(source)
                sources.drop(i + 1).firstOrNull { it.show }?.let { selectSource(it, auto = true); return@launch }
            }
            if (list.isEmpty()) note = if (answered) "Источник ничего не нашёл. Выберите другой." else "Источник не ответил. Выберите другой."
            idx = startIndex()
            if (list.isEmpty()) zone = Zone.CHIPS else if (picker == null) zone = Zone.LIST
        }
    }

    /**
     * Variants of a film (not episodes, those keep their order): torrents with a size biggest first, everything else by
     * quality, best first. Sorting is stable, so the source's own order stays among equals.
     */
    private fun arrange(list: List<Showy.Item>): List<Showy.Item> = when {
        list.isNotEmpty() && list.all { it.episode > 0 } -> list
        list.any { it.sizeGb != null } -> list.sortedByDescending { it.sizeGb ?: 0.0 }
        else -> list.sortedByDescending { Showy.qualityRank(quality(it)) }
    }

    private fun takeVoices(list: List<Showy.Item>) {
        voices = list
        voice = list.indexOfFirst { it.active }.takeIf { it >= 0 } ?: voice.coerceIn(0, list.lastIndex)
    }

    /** The file to put the focus on: for a series the episode after the last one finished, for a film the last voice. */
    private fun startIndex(): Int {
        val l = files
        if (l.isEmpty()) return 0
        if (!serial) return l.indexOfFirst { it.text == choice.voice }.coerceAtLeast(0)
        var last = -1
        l.forEachIndexed { i, f -> if (history.mark(title.key, f.season, f.episode) != null) last = i }
        if (last < 0) return 0
        val m = history.mark(title.key, l[last].season, l[last].episode)
        return if (m?.done == true) (last + 1).coerceAtMost(l.lastIndex) else last
    }

    fun mark(item: Showy.Item): History.Mark? = history.mark(title.key, item.season, item.episode)

    /** The file's season number: its own `s`, else the picked season's "2 сезон". */
    fun seasonOf(item: Showy.Item): Int =
        item.season.takeIf { it > 0 } ?: seasons.getOrNull(season)?.text?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 1

    fun episode(item: Showy.Item): Episode? =
        if (!serial || item.episode <= 0) null else episodes[seasonOf(item)]?.get(item.episode)

    private fun requestSeason(n: Int) {
        if (!serial || !seasonsAsked.add(n)) return
        jobs += scope.launch { Tmdb.season(title.id, n)?.let { episodes[n] = it } ?: seasonsAsked.remove(n) }
    }

    /** What the source says about the file's quality, without asking it. */
    fun quality(item: Showy.Item): String {
        // ShowyTor writes "2160" without the p.
        item.json.optString("maxquality").takeIf { it.isNotEmpty() }?.let { return if (it.all(Char::isDigit)) "${it}p" else it }
        item.json.optJSONObject("quality")?.keys()?.asSequence()?.maxByOrNull { Showy.qualityRank(it) }?.let { return it }
        item.resolution?.let { return it }
        return qualities[item.url] ?: ""
    }

    /** For `call` files the quality comes with the stream link: asked once the focus rests on the row. */
    fun probe(item: Showy.Item) {
        if (item.method != "call" || quality(item).isNotEmpty() || busy) return
        probeJob?.cancel()
        probeJob = scope.launch {
            delay(450)
            val s = runCatching { Showy.stream(item) }.getOrNull() ?: return@launch
            if (s.quality.isNotEmpty()) qualities[item.url] = s.quality
        }
    }

    // ---------- Playing ----------

    /** Plays [i] in ViMu; a series continues with the following episodes as a ViMu playlist. */
    fun play(i: Int, launch: (Intent, (Int, Intent?) -> Unit) -> Boolean) {
        val f = files.getOrNull(i) ?: return
        if (busy) return
        pageJob?.cancel()
        pageJob = scope.launch {
            busy = true
            note = ""
            val first = try { Showy.stream(f) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            busy = false
            if (first == null) { note = "Нет ссылки на видео. Попробуйте другой источник."; return@launch }
            val items = arrayListOf(f)
            val urls = arrayListOf(first.url)
            if (serial) for (next in files.drop(i + 1).take(30)) {
                val u = Showy.direct(next) ?: break
                items += next
                urls += u
            }
            val names = items.map { label(it) }
            val mark = mark(f)
            val start = if (mark != null && !mark.done && mark.positionMs > 10_000) mark.positionMs - 5_000 else 0L
            // A series keeps the voice it played in even when it was the page's default, for the new-episode check.
            val played = if (serial) choice.copy(voice = voices.getOrNull(voice)?.text ?: choice.voice) else choice.copy(voice = f.text)
            history.saveChoice(title.key, played.copy(source = source?.key ?: ""))
            // Written before ViMu starts: if the launcher is killed during playback, the title is still in «Продолжить».
            val before = history.entry(title.key)
            history.played(title, f.season, f.episode, label(f), mark?.positionMs ?: 0, mark?.durationMs ?: 0, false)
            onPlayed()
            val ok = launch(Vimu.intent(urls, names, start, first.headers)) { code, data ->
                result(items, urls, Vimu.result(code, data), before)
            }
            if (!ok) note = "ViMu не установлен"
        }
    }

    private fun label(f: Showy.Item): String =
        if (serial) listOfNotNull(seasons.getOrNull(season)?.text ?: f.season.takeIf { it > 0 }?.let { "$it сезон" }, f.text).joinToString(" · ")
        else title.name

    private fun result(items: List<Showy.Item>, urls: List<String>, r: Vimu.Result, before: org.json.JSONObject?) {
        android.util.Log.i("homescreen", "vimu: ended=${r.ended} failed=${r.failed} pos=${r.positionMs} dur=${r.durationMs} item=${r.url?.let(urls::indexOf)}")
        if (r.failed) note = "ViMu не смог открыть видео. Попробуйте другой источник или озвучку."
        val at = r.url?.let { u -> urls.indexOf(u).takeIf { it >= 0 } } ?: if (r.ended) items.lastIndex else 0
        for (k in 0 until at) {
            val f = items[k]
            history.played(title, f.season, f.episode, label(f), 0, 0, true)
        }
        val f = items[at]
        // A file ViMu could not open was not watched: «Продолжить» goes back to what it was.
        if (r.failed && at == 0) history.restore(title.key, before)
        else if (!r.failed) history.played(title, f.season, f.episode, label(f), r.positionMs, r.durationMs, r.ended)
        marks++
        idx = startIndex()
        onPlayed()
    }

    companion object {
        const val DEFAULT_SOURCE = "fxapi"
    }
}
