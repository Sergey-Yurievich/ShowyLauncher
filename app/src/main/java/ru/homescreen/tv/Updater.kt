package ru.homescreen.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import ru.homescreen.tv.data.Net
import java.io.File

/**
 * Updates from GitHub releases, installed only when asked (the gear menu's «Обновить»). The release's
 * ShowyLauncher.apk is downloaded, must carry this app's own signature, and goes to Android's installer; on Android
 * 12+ a launcher that scripts/install.sh made its own installer of record updates without a system prompt.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/Sergey-Yurievich/ShowyLauncher/releases/latest"
    private const val ASSET = "ShowyLauncher.apk"

    data class Release(val version: String, val url: String)

    /** A newer release than this build, once a check found one. */
    var available by mutableStateOf<Release?>(null)
        private set
    /** Downloading or handing over to the installer: the menu item waits. */
    var busy by mutableStateOf(false)
        private set
    /** What went wrong with the last «Обновить», for a toast; cleared when read. */
    var problem by mutableStateOf<String?>(null)

    suspend fun check() {
        val j = Net.json(LATEST) ?: return
        val version = j.optString("tag_name").removePrefix("v")
        val assets = j.optJSONArray("assets") ?: return
        val url = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == ASSET }?.optString("browser_download_url") ?: return
        available = if (newer(version, BuildConfig.VERSION_NAME)) Release(version, url) else null
    }

    /** "1.10" > "1.9": compared number by number. */
    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun install(context: Context) {
        val r = available ?: return
        if (busy) return
        busy = true
        try {
            val apk = File(context.cacheDir, "update.apk")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Net.client.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@use false
                        apk.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                        true
                    }
                }.getOrDefault(false)
            }
            if (!ok) { problem = "Не удалось скачать обновление"; return }
            if (!sameSigner(context, apk)) { problem = "Обновление подписано чужим ключом — не ставлю"; apk.delete(); return }
            withContext(Dispatchers.IO) { commit(context, apk) }
        } finally {
            busy = false
        }
    }

    /** The downloaded APK is this app, newer, and signed by the same key: anything else is refused. */
    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (theirs.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, flags)
        if (theirs.longVersionCode <= mine.longVersionCode) return false
        val a = theirs.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return false
        val b = mine.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return false
        return a == b
    }

    /**
     * Replaced by its own update, the launcher would leave the TV on whatever app was open before. The mark set here
     * is read by [KeysService] once Android binds it to the new version: it brings Home back (a broadcast receiver
     * may not open screens from the background).
     */
    fun takeJustUpdated(context: Context): Boolean {
        val p = context.getSharedPreferences("homescreen", Context.MODE_PRIVATE)
        if (!p.getBoolean("updating", false)) return false
        p.edit().putBoolean("updating", false).apply()
        return true
    }

    private fun commit(context: Context, apk: File) {
        context.getSharedPreferences("homescreen", Context.MODE_PRIVATE).edit().putBoolean("updating", true).apply()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite(ASSET, 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, StatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(status.intentSender)
        }
    }

    /** The installer's answer: a confirmation screen to show, or why it failed. On success the app is replaced. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let {
                        context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> problem = "Обновление не установилось: " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка установщика")
            }
        }
    }
}
