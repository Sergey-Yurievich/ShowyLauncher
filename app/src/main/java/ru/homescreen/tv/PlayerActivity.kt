package ru.homescreen.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.homescreen.tv.data.Net
import ru.homescreen.tv.data.PlayerPrefs
import ru.homescreen.tv.data.Prefetch
import ru.homescreen.tv.data.Segments
import ru.homescreen.tv.data.Subs
import ru.homescreen.tv.ui.LocalScale
import ru.homescreen.tv.ui.LocalScaleHolder
import ru.homescreen.tv.ui.PlayerScreen
import java.util.Locale

/**
 * The built-in player, for two subtitle languages at once from a file's own tracks (ShowyTor's MKVs), which ViMu shows
 * only one at a time. Media3 reads the stream as it plays and hands the video to the TV's decoders through a SurfaceView,
 * HDR and Dolby Vision included. Two text renderers run side by side: the first takes the upper (English) line, the
 * second the lower (Russian) one. What it plays comes from [Playback.current]; a series goes on from episode to episode
 * here, each position written to the launcher's history as it goes. The result is 1 at the end, 4 on an error, with
 * `opened` false when Media3 couldn't read the file at all (the launcher then gives it to ViMu).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerControls.Host {
    private lateinit var player: ExoPlayer
    private lateinit var selector: TwoSubtitles
    private lateinit var controls: PlayerControls
    private lateinit var playback: Playback
    private var current: Playback.Opened? = null
    /** The file being downloaded to disk ahead of the player; null for HLS and servers without ranges. */
    @Volatile private var prefetch: Prefetch? = null
    private var loadJob: Job? = null
    /** Some file got as far as playing: an error after that is not Media3 failing to read it. */
    private var opened = false
    /** TheIntroDB is asked once per episode, when its length is known. */
    private var segmentsAsked = false
    private var ended = false
    /** The voice to keep from episode to episode (language and label): the first one played, or the one picked. */
    private var voice: Pair<String?, String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playback = Playback.current ?: run { finish(); return }
        Net.init(this)
        PlayerPrefs.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controls = PlayerControls(this, playback)
        // Files left by a player the system killed.
        File(cacheDir, "video").deleteRecursively()

        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>,
            ) {
                out.add(TextRenderer({ controls.upper = text(it.cues) }, outputLooper))
                out.add(TextRenderer({ controls.lower = text(it.cues) }, outputLooper))
            }
        }
        val main = Handler(Looper.getMainLooper())
        selector = TwoSubtitles(this, PlayerPrefs.subtitles(playback.key)) { tracks, upper, lower ->
            main.post {
                controls.texts = tracks
                controls.textUpper = upper
                controls.textLower = lower
            }
        }
        // HLS with several qualities starts at the top: the TV is on a home network, not a phone's.
        val meter = DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(30_000_000).build()
        // The buffer lives in the Java heap: Media3's default (up to ~145 MB for video and sound) ran the launcher out of
        // memory on the Android 12 TV (192 MB per app) a few minutes into a 4K file.
        // After a stall it waits for a longer run than Media3's 5 s, so a slow torrent stops less often.
        val buffer = DefaultLoadControl.Builder().setTargetBufferBytes(BUFFER_BYTES)
            .setBufferDurationsMs(50_000, 50_000, 5_000, 15_000).build()
        player = ExoPlayer.Builder(this, renderers).setTrackSelector(selector).setBandwidthMeter(meter).setLoadControl(buffer).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                controls.loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) {
                    opened = true
                    askSegments()
                }
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("homescreen", "player: ${error.errorCodeName} opened=$opened in ${playback.title} #${controls.index}", error)
                done(4)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.i("homescreen", "player: play=$playWhenReady reason=$reason at ${player.currentPosition}")
            }

            override fun onTracksChanged(tracks: Tracks) {
                keepVoice()
                val groups = audioGroups()
                controls.audio = groups.mapIndexed { i, g -> describeAudio(g.getTrackFormat(0), i) }
                controls.audioSelected = groups.indexOfFirst { it.isSelected }
                controls.badges = badges()
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                Log.i("homescreen", "player: video decoder $decoderName")
            }

            override fun onVideoInputFormatChanged(t: AnalyticsListener.EventTime, format: Format, evaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                Log.i("homescreen", "player: video ${format.sampleMimeType} ${format.codecs} ${format.width}x${format.height} ${format.colorInfo}")
                controls.badges = badges()
            }

            override fun onAudioInputFormatChanged(t: AnalyticsListener.EventTime, format: Format, evaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                controls.badges = badges()
            }
        })

        val view = PlayerView(this).apply {
            useController = false
            player = this@PlayerActivity.player
            subtitleView?.visibility = View.GONE
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        setContent {
            val scale = LocalConfiguration.current.screenWidthDp / 1920f
            LocalScaleHolder.px = scale * LocalDensity.current.density
            CompositionLocalProvider(LocalScale provides scale) { PlayerScreen(controls, view) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var reported = SystemClock.uptimeMillis()
                while (true) {
                    val now = SystemClock.uptimeMillis()
                    controls.tick(now)
                    // Also now and then: if the process is killed under the player, «Продолжить» is no more than a minute behind.
                    if (now - reported > 60_000 && player.isPlaying) { report(); reported = now }
                    delay(250)
                }
            }
        }
        load(playback.start)
    }

    /** Asks for an episode's link and starts it; the old one keeps playing until the new link is there. */
    private fun load(i: Int) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            controls.loading = true
            val o = playback.open(i)
            if (o == null) {
                if (current == null) done(4) else {
                    controls.loading = false
                    controls.say("Серия не открылась", SystemClock.uptimeMillis())
                }
                return@launch
            }
            current = o
            segmentsAsked = false
            ended = false
            controls.startItem(i)
            // The last file's track means nothing in this one: [keepVoice] picks the voice again once the tracks are known.
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            val old = prefetch
            val (hls, subs, disk) = coroutineScope {
                val h = async { isHls(o.url, o.headers) }
                val t = async { sideload(o.subtitles) }
                // Asked alongside: a torrent's first answer can take seconds.
                val d = async(Dispatchers.IO) { if (h.await()) null else Prefetch.open(File(cacheDir, "video/${SystemClock.uptimeMillis()}"), o.url, o.headers) }
                Triple(h.await(), t.await(), d.await())
            }
            prefetch = disk
            val item = MediaItem.Builder().setUri(o.url).setSubtitleConfigurations(subs)
                .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            // The sideloaded subtitles are files in the cache: plain file:// next to the stream's http(s).
            val net = if (disk != null) DataSource.Factory { Prefetch.Source(disk) }
                else OkHttpDataSource.Factory(Net.client).setDefaultRequestProperties(o.headers)
            val data = DefaultDataSource.Factory(this@PlayerActivity, net)
            player.setMediaSource(DefaultMediaSourceFactory(data).createMediaSource(item), o.startMs)
            player.prepare()
            player.playWhenReady = true
            // The last episode's file goes once the player has let go of it.
            old?.let { try { delay(5_000) } finally { it.close() } }
        }
    }

    /**
     * The source's own subtitle tracks, saved as UTF-8 files (older Russian ones come in Windows-1251, which Media3
     * would garble) and handed to Media3 beside the file's embedded ones.
     */
    private suspend fun sideload(tracks: List<Subs.Track>): List<MediaItem.SubtitleConfiguration> = coroutineScope {
        val dir = File(cacheDir, "subs").apply { deleteRecursively(); mkdirs() }
        tracks.take(12).mapIndexed { k, t -> async { Subs.fetch(t.url)?.let { k to it } } }.mapNotNull { it.await() }.map { (k, text) ->
            val t = tracks[k]
            val (ext, mime) = when {
                text.trimStart().startsWith("WEBVTT") -> "vtt" to MimeTypes.TEXT_VTT
                "[Script Info]" in text -> "ass" to MimeTypes.TEXT_SSA
                else -> "srt" to MimeTypes.APPLICATION_SUBRIP
            }
            val file = File(dir, "$k.$ext").apply { writeText(text) }
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(mime).setLabel(t.label)
                .setLanguage(Subs.language(t.label)).setId("link$k")
                .setSelectionFlags(if (Subs.rank(t.label) == 2) C.SELECTION_FLAG_FORCED else 0).build()
        }
    }

    /**
     * Whether the link is HLS. Media3 tells by the extension; Showy's proxied links often have none, so their first
     * bytes are asked for. Files (a torrent's /stream/, a .mp4) are not.
     */
    private suspend fun isHls(url: String, headers: Map<String, String>): Boolean {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        if (".m3u8" in path || path.endsWith(".m3u")) return true
        if ("/stream/" in path || FILES.any { path.endsWith(it) }) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val b = Request.Builder().url(url).header("Range", "bytes=0-15")
                headers.forEach { (k, v) -> b.header(k, v) }
                Net.client.newCall(b.build()).execute().use { r ->
                    r.isSuccessful && r.body?.source()?.let { it.request(7) && it.buffer.readUtf8(7) == "#EXTM3U" } == true
                }
            }.getOrDefault(false)
        }.also { Log.i("homescreen", "player: ${if (it) "hls" else "file"} by its first bytes") }
    }

    private fun askSegments() {
        if (segmentsAsked) return
        segmentsAsked = true
        val item = controls.item
        val d = player.duration.takeIf { it > 0 } ?: 0L
        lifecycleScope.launch {
            val m = if (playback.serial) Segments.of(playback.tmdbId, item.season, item.episode, d) else Segments.of(playback.tmdbId, null, null, d)
            if (m != null && controls.item == item) controls.segments = m
            Log.i("homescreen", "player: segments $m for s${item.season}e${item.episode}")
        }
    }

    private fun onEnded() {
        if (ended) return
        ended = true
        report(true)
        if (controls.hasNext) load(controls.index + 1) else done(1)
    }

    /** Where the current episode stands goes to the launcher's history. */
    private fun report(end: Boolean = false) {
        if (current == null) return
        val d = player.duration.takeIf { it > 0 } ?: return
        val p = if (end) d else player.currentPosition
        playback.report(controls.index, p, d, end || p >= controls.watchedAt)
    }

    // ---------- PlayerControls.Host ----------

    override val position: Long get() = player.currentPosition
    override val duration: Long get() = player.duration.takeIf { it > 0 } ?: 0L
    override val buffered: Long get() = player.bufferedPosition
    override val loaded: Float? get() = prefetch?.loaded
    override val speed: Long get() = prefetch?.speed ?: 0L
    override val playing: Boolean get() = player.playWhenReady

    override fun seekTo(ms: Long) = player.seekTo(ms)

    override fun setPlaying(on: Boolean) {
        player.playWhenReady = on
    }

    override fun chooseAudio(i: Int) {
        val g = audioGroups().getOrNull(i) ?: return
        voice = g.getTrackFormat(0).let { it.language to it.label }
        override(g)
    }

    private fun override(g: Tracks.Group) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    /**
     * Each file has its own default voice (one episode Russian, the next English): the one played first, or picked in
     * the menu, is chosen again in every episode that has it, by language and label, else by language.
     */
    private fun keepVoice() {
        val groups = audioGroups()
        val now = groups.firstOrNull { it.isSelected }?.getTrackFormat(0) ?: return
        val want = voice ?: run { voice = now.language to now.label; return }
        if (now.language == want.first && now.label == want.second) return
        val match = groups.firstOrNull { g -> g.getTrackFormat(0).let { it.language == want.first && it.label == want.second } }
            ?: groups.firstOrNull { it.getTrackFormat(0).language == want.first }
        if (match != null && !match.isSelected) override(match)
    }

    override fun chooseText(line: Int, key: String) {
        if (line == 0) selector.choose(key, selector.lower) else selector.choose(selector.upper, key)
        // Kept as languages for the title: its next episode (or next evening) starts with the same lines.
        val lang = controls.texts.firstOrNull { it.key == key }?.lang.orEmpty()
        val (upper, lower) = PlayerPrefs.subtitles(playback.key)
        PlayerPrefs.setSubtitles(playback.key, if (line == 0) lang else upper, if (line == 1) lang else lower)
    }

    override fun playEpisode(i: Int) {
        if (i !in playback.items.indices) return
        report()
        load(i)
    }

    override fun exit() = done(0)

    // ---------- Activity ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::controls.isInitialized) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && controls.key(event.keyCode, event.repeatCount, SystemClock.uptimeMillis())) return true
        // The other half of a key handled above; Back's release would otherwise close the screen as well.
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in HANDLED) return true
        return super.dispatchKeyEvent(event)
    }

    /** The answer is kept current: Home closes this screen without a word, and the position must survive that. */
    override fun onPause() {
        super.onPause()
        if (!::player.isInitialized) return
        if (!isFinishing) {
            report()
            answer(0)
        }
        player.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
        prefetch?.close()
    }

    private fun answer(code: Int) {
        setResult(code, Intent().putExtra("opened", opened).putExtra("item", controls.index)
            .putExtra("position", player.currentPosition.toInt()).putExtra("duration", duration.toInt()))
    }

    private fun done(code: Int) {
        if (isFinishing) return
        if (code != 4) report(code == 1)
        answer(code)
        finish()
    }

    // ---------- Tracks ----------

    private fun audioGroups(): List<Tracks.Group> = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }

    private fun describeAudio(f: Format, i: Int): PlayerControls.Track {
        val lang = languageName(f.language)
        val label = f.label?.trim()?.takeIf { it.isNotEmpty() && !namesLanguage(it, f.language) }
        return PlayerControls.Track("$i", lang ?: label ?: "Дорожка ${i + 1}", listOfNotNull(label.takeIf { lang != null }, codec(f)).joinToString(" · "))
    }

    /** Short facts for the panel's corner: 4K, Dolby Vision or HDR10, 5.1. */
    private fun badges(): List<String> = buildList {
        player.videoFormat?.let { v ->
            when {
                v.width >= 3000 || v.height >= 1600 -> add("4K")
                v.width >= 1800 -> add("1080p")
                v.width >= 1200 -> add("720p")
            }
            val dv = v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || v.codecs?.startsWith("dv") == true
            when {
                dv -> add("Dolby Vision")
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> add("HDR10")
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> add("HLG")
            }
        }
        player.audioFormat?.let { a ->
            if (a.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC) add("Atmos")
            else channels(a.channelCount)?.let(::add)
        }
    }

    private fun text(cues: List<androidx.media3.common.text.Cue>): String =
        cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /**
     * The upper line's track to the first text renderer, the lower one's to the second: what was picked in the menu, else
     * the best ones (full before SDH and forced) in the languages last picked for the title; a new title starts with
     * none. Picture tracks (Blu-ray's PGS, DVD's VobSub) are left out: the lines draw text only. [published] gets the
     * choice back on the playback thread.
     */
    private class TwoSubtitles(
        context: Context,
        /** The languages last picked for this title, upper and lower; "" (or a title never set): that line is off. */
        private val remembered: Pair<String, String>,
        private val published: (List<PlayerControls.Track>, String, String) -> Unit,
    ) : DefaultTrackSelector(context) {
        /** The picked tracks' keys; null for the automatic choice, [PlayerControls.OFF] for none. */
        @Volatile var upper: String? = null
            private set
        @Volatile var lower: String? = null
            private set

        fun choose(upper: String?, lower: String?) {
            this.upper = upper
            this.lower = lower
            invalidate(parameters)
        }

        override fun selectAllTracks(
            definitions: Array<ExoTrackSelection.Definition?>,
            mappedTrackInfo: MappedTrackInfo,
            rendererFormatSupports: Array<Array<IntArray>>,
            rendererMixedMimeTypeAdaptationSupports: IntArray,
            params: Parameters,
        ) {
            super.selectAllTracks(definitions, mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
            val text = (0 until mappedTrackInfo.rendererCount).filter { mappedTrackInfo.getRendererType(it) == C.TRACK_TYPE_TEXT }
            if (text.size < 2) return
            // Every text track is mapped to the first text renderer; the second one is handed its track from there.
            val all = mappedTrackInfo.getTrackGroups(text[0]).let { g -> (0 until g.length).map { g[it] } }
            val groups = all.filter { !picture(it.getFormat(0)) }
            val keyed = keys(groups)
            val up = pick(keyed, upper) { best(keyed, remembered.first) }
            // One track can't feed both renderers.
            val down = pick(keyed, lower) { best(keyed, remembered.second) }?.takeIf { it.second != up?.second }
            definitions[text[0]] = up?.let { ExoTrackSelection.Definition(it.second, 0) }
            definitions[text[1]] = down?.let { ExoTrackSelection.Definition(it.second, 0) }
            published(keyed.map { (k, g) -> describe(k, g.getFormat(0)) }, up?.first ?: PlayerControls.OFF, down?.first ?: PlayerControls.OFF)
        }

        /** Language, label and the forced flag, numbered among equals: the same track keeps its key from episode to episode. */
        private fun keys(groups: List<TrackGroup>): List<Pair<String, TrackGroup>> {
            val seen = HashMap<String, Int>()
            return groups.map { g ->
                val f = g.getFormat(0)
                val base = "${f.language}|${f.label}|${f.selectionFlags and C.SELECTION_FLAG_FORCED}"
                val n = seen.merge(base, 1, Int::plus)!!
                "$base|$n" to g
            }
        }

        private fun pick(keyed: List<Pair<String, TrackGroup>>, key: String?, auto: () -> Pair<String, TrackGroup>?) = when (key) {
            PlayerControls.OFF -> null
            null -> auto()
            else -> keyed.firstOrNull { it.first == key } ?: auto()
        }

        private fun best(keyed: List<Pair<String, TrackGroup>>, language: String) = if (language.isEmpty()) null else keyed.filter { (_, g) ->
            languageOf(g.getFormat(0)) == language
        }.minByOrNull { (_, g) ->
            val f = g.getFormat(0)
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) 2 else Subs.rank(f.label ?: "")
        }

        private fun describe(key: String, f: Format): PlayerControls.Track {
            val label = f.label?.trim().orEmpty()
            val sub = when {
                f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || Subs.rank(label) == 2 -> "Только надписи"
                Subs.rank(label) == 1 -> "С описанием звуков (SDH)"
                label.isNotEmpty() && !namesLanguage(label, f.language) -> label
                else -> "Полные"
            }
            return PlayerControls.Track(key, languageName(languageOf(f)) ?: label.ifEmpty { "Субтитры" }, sub, languageOf(f))
        }
    }

    companion object {
        /** About 10 s of an 80 Mbit/s 4K remux, half a minute and more of anything lighter. */
        private const val BUFFER_BYTES = 96 shl 20
        private val HANDLED = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
        private val PICTURES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)
        private val FILES = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".ts", ".m4v", ".mpg")

        /** Parsed while read, a subtitle track keeps its original type in codecs. */
        private fun picture(f: Format) = f.sampleMimeType in PICTURES || f.codecs in PICTURES

        private fun languageOf(f: Format): String? = f.language?.take(2)?.lowercase()?.takeIf { it != "un" } ?: Subs.language(f.label ?: "")

        /** A label that only repeats the language: "Eng", "English", "Ukrainian", "Русский". */
        private fun namesLanguage(label: String, code: String?): Boolean {
            if (Subs.language(label) != null) return true
            val c = code?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: return false
            val l = Locale.forLanguageTag(c)
            return label.equals(c, true) || label.equals(l.getDisplayLanguage(Locale.ENGLISH), true) ||
                label.equals(l.getDisplayLanguage(Locale("ru")), true)
        }

        private fun languageName(code: String?): String? {
            val c = code?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
            val name = Locale.forLanguageTag(c).getDisplayLanguage(Locale("ru"))
            return name.takeIf { it.isNotEmpty() && it != c }?.replaceFirstChar { it.uppercase() }
        }

        private fun channels(n: Int): String? = when (n) {
            1 -> "Моно"
            2 -> "Стерео"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }

        private fun codec(f: Format): String? {
            val name = when (f.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
                MimeTypes.AUDIO_E_AC3 -> "E-AC3"
                MimeTypes.AUDIO_AC3 -> "AC3"
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS-HD"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                MimeTypes.AUDIO_MPEG -> "MP3"
                else -> null
            }
            return listOfNotNull(name, channels(f.channelCount)).joinToString(" ").ifEmpty { null }
        }

        /** What to play is in [Playback.current], set just before. */
        fun intent(): Intent = Intent().setClassName(BuildConfig.APPLICATION_ID, PlayerActivity::class.java.name)
    }
}
