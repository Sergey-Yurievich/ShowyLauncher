package ru.homescreen.tv

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The remote's buttons that only the stock Google TV home answered, so with it gone they would do nothing. Settings
 * (TCL remotes send keycode 83, KEYCODE_NOTIFICATION) opens Android's settings; Input (KEYCODE_TV_INPUT, or KEYCODE_TV)
 * opens this launcher's Inputs menu over whatever is on, instead of TCL's panel with its app ads. Every other key
 * passes through untouched. scripts/install.sh switches it on over adb.
 */
class KeysService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val settings = code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_NOTIFICATION
        val input = code == KeyEvent.KEYCODE_TV_INPUT || code == KeyEvent.KEYCODE_TV
        if (!settings && !input) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            val open = if (settings) Intent(Settings.ACTION_SETTINGS)
            else Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_INPUTS)
            try {
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    companion object {
        /**
         * Puts this service back among the enabled accessibility services when it fell out: force-stopping the launcher
         * (Settings → Force stop, a "cleaner" app) clears the whole list, and the remote's buttons go dead. Needs
         * WRITE_SECURE_SETTINGS, which only adb can grant (scripts/install.sh does); without it this does nothing.
         */
        fun ensureEnabled(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
            val me = ComponentName(context, KeysService::class.java)
            val cr = context.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val listed = cur.split(':').any { ComponentName.unflattenFromString(it) == me }
            try {
                if (!listed) {
                    val entry = me.flattenToShortString()
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) entry else "$cur:$entry")
                }
                if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            } catch (_: SecurityException) {
            }
        }
    }
}
