package ru.homescreen.tv.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lampa (top.rootu.lampa): opening titles in it and reading the rows it publishes to the TV provider. */
object Lampa {
    const val PKG = "top.rootu.lampa"
    private val ACTIVITY = ComponentName(PKG, "$PKG.MainActivity")

    /**
     * Opens the title's card in Lampa. Lampa reads `id`/`media`/`source` (MainActivity.processIntent); `cub` loads the
     * card through Lampa's own server, which works where direct TMDB does not.
     */
    fun openCard(context: Context, t: Title): Boolean = start(
        context,
        Intent(Intent.ACTION_VIEW).setComponent(ACTIVITY)
            .putExtra("id", t.id.toString())
            .putExtra("media", t.media.path)
            .putExtra("source", "cub"),
    )

    /** Resumes a «Продолжить просмотр» entry the way Lampa wrote it (its own intent, with the player position). */
    fun resume(context: Context, tile: ContinueTile): Boolean {
        val uri = tile.intentUri
        if (uri != null) {
            try {
                val i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                if (start(context, i)) return true
            } catch (_: Exception) {
            }
        }
        return openCard(context, tile.title)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /** Rows the plugin published (`AndroidJS.publishPluginChannel`), by channel title. Empty when there are none. */
    suspend fun pluginChannel(context: Context, title: String): Pair<Boolean, List<ContinueTile>> = withContext(Dispatchers.IO) {
        try {
            var channelId = -1L
            context.contentResolver.query(TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID, "package_name", TvContract.Channels.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == PKG && c.getString(2) == title) channelId = c.getLong(0)
            }
            if (channelId < 0) return@withContext false to emptyList()
            val out = ArrayList<Pair<Int, ContinueTile>>()
            context.contentResolver.query(TvContract.PreviewPrograms.CONTENT_URI, arrayOf(
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_TYPE,
                TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_WEIGHT, TvContract.PreviewPrograms.COLUMN_RELEASE_DATE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) != channelId) continue
                    val id = c.getString(1)?.toIntOrNull() ?: continue
                    val tv = c.getInt(3) == TvContract.PreviewPrograms.TYPE_TV_SERIES || c.getInt(3) == TvContract.PreviewPrograms.TYPE_TV_EPISODE
                    val t = Title(id, if (tv) Media.TV else Media.MOVIE, c.getString(2) ?: continue, c.getString(7)?.take(4) ?: "",
                        0.0, c.getString(4) ?: "", null, null, emptyList())
                    out += c.getInt(6) to ContinueTile(t, c.getString(5), 0f, "", null)
                }
            }
            true to out.sortedByDescending { it.first }.map { it.second }
        } catch (e: Exception) {
            android.util.Log.w("homescreen", "plugin channel: $e")
            false to emptyList()
        }
    }

    /**
     * Lampa's Watch Next entries. Needs READ_EPG_DATA (granted with `pm grant` at install); without it the provider
     * returns nothing and the row is simply left out.
     */
    suspend fun continueWatching(context: Context): List<ContinueTile> = withContext(Dispatchers.IO) {
        val c = TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS
        val cols = arrayOf(
            "package_name",
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_TYPE,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            c,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_RELEASE_DATE,
            TvContract.WatchNextPrograms.COLUMN_REVIEW_RATING,
        )
        val out = ArrayList<Pair<Long, ContinueTile>>()
        try {
            context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(0) != PKG) continue
                    val id = cur.getString(1)?.toIntOrNull() ?: continue
                    val type = cur.getInt(3)
                    val watchType = cur.getInt(4)
                    if (watchType == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST) continue
                    val media = if (type == TvContract.WatchNextPrograms.TYPE_MOVIE) Media.MOVIE else Media.TV
                    val pos = cur.getLong(8)
                    val dur = cur.getLong(9)
                    val progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val left = if (pos > 0 && dur > pos) ((dur - pos) / 60_000).toInt() else 0
                    val title = Title(
                        id = id,
                        media = media,
                        name = cur.getString(2) ?: continue,
                        year = cur.getString(12)?.take(4) ?: "",
                        vote = (cur.getString(13)?.toDoubleOrNull() ?: 0.0) * 2,
                        overview = cur.getString(5) ?: "",
                        poster = null,
                        backdrop = null,
                        genreIds = emptyList(),
                    )
                    out += cur.getLong(11) to ContinueTile(
                        title = title,
                        image = cur.getString(7) ?: cur.getString(6),
                        progress = progress,
                        label = when {
                            left >= 60 -> "осталось ${left / 60} ч ${left % 60} мин"
                            left > 0 -> "осталось $left мин"
                            else -> ""
                        },
                        intentUri = cur.getString(10),
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("homescreen", "watch next: " + e)
            return@withContext emptyList()
        }
        android.util.Log.i("homescreen", "watch next: ${out.size} from Lampa")
        out.sortedByDescending { it.first }.map { it.second }.distinctBy { it.key }
    }
}
