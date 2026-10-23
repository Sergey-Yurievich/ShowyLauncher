package ru.homescreen.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
}
