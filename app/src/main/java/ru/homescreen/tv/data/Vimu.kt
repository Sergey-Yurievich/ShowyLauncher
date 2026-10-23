package ru.homescreen.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * ViMu, the player Lampa used. Started for a result (https://www.vimu.tv/player-api): 0 = stopped by the user with
 * `position`/`duration`, 1 = played to the end, 4 = error. For a playlist the result also names the `url` it stopped on.
 */
object Vimu {
    const val PKG = "net.gtvbox.vimuhd"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** One file, or a playlist starting at [urls]`[0]` when there are more (the following episodes). */
    fun intent(urls: List<String>, names: List<String>, startMs: Long, headers: Map<String, String>): Intent {
        val i = Intent(Intent.ACTION_VIEW).setPackage(PKG)
        if (urls.size <= 1) {
            i.setDataAndType(Uri.parse(urls.first()), "video/*")
            i.putExtra("forcename", names.firstOrNull() ?: "")
        } else {
            i.setDataAndType(Uri.parse("https://dummy"), "application/vnd.gtvbox.filelist")
            i.putStringArrayListExtra("asusfilelist", ArrayList(urls))
            i.putStringArrayListExtra("asusnamelist", ArrayList(names))
        }
        if (startMs > 0) {
            i.putExtra("startfrom", startMs.toInt())
            i.putExtra("position", startMs.toInt())
        }
        if (headers.isNotEmpty()) i.putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        i.putExtra("forcedirect", true)
        i.putExtra("forceresume", false)
        return i
    }

    data class Result(val ended: Boolean, val failed: Boolean, val positionMs: Long, val durationMs: Long, val url: String?)

    fun result(code: Int, data: Intent?): Result = Result(
        ended = code == 1,
        failed = code == 4,
        positionMs = data?.getIntExtra("position", 0)?.toLong() ?: 0L,
        durationMs = data?.getIntExtra("duration", 0)?.toLong() ?: 0L,
        url = data?.getStringExtra("url") ?: data?.dataString,
    )
}
