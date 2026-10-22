package tv.aura.homeguard

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/** Fire OS fallback: mask only Amazon HOME, never Settings/Alexa or arbitrary launcher windows. */
class AuraHomeService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var curtain: HomeCurtain
    private var escapeUntil = 0L
    private var pending = false
    private var lastAttempt = -2_000L
    private var screenOn = true
    private val failures by lazy { getSharedPreferences("homeguard-launch-safety", Context.MODE_PRIVATE) }
    internal val curtainVisible get() = ::curtain.isInitialized && curtain.visible

    private val timeout = Runnable {
        pending = false
        HomeGuard.triggerAt = 0
        HomeGuard.detail = "Home launch timed out; curtain removed for safety"
        curtain.hide()
        trace("LAUNCH_TIMEOUT")
        if (failures.getInt("attempts", 0) >= 3) pauseFailedLaunches()
    }
    private val accessibilityObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (HomeGuard.homeOnFireEnabled(this@AuraHomeService)) {
                cancelTransition()
                HomeGuard.detail = "Passive: Home on Fire is enabled"
            } else HomeGuard.detail = null
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; cancelTransition() }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    // Preserve foreground app on wake; redirect only actual Amazon HOME windows.
                }
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    // AOSP emits globalactions for long-HOME/power menus. Firmware validation pending.
                    if (intent.getStringExtra("reason") == "globalactions") allowAmazon(30_000)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        HomeGuard.initialize(application as Application)
        HomeGuard.service = this
        curtain = HomeCurtain(this)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, accessibilityObserver)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        HomeGuard.detail = if (HomeGuard.homeOnFireEnabled(this)) "Passive: Home on Fire is enabled" else null
        trace("CONNECTED passive=${HomeGuard.homeOnFireEnabled(this)} flags=${serviceInfo.flags}")
    }

    private fun active() = screenOn && !HomeGuard.homeOnFireEnabled(this) && SystemClock.uptimeMillis() >= escapeUntil

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        if (pkg == packageName) return // remove curtain after Aura's draw, not on its first window event
        if (HomeGuard.homeOnFireEnabled(this)) { cancelTransition(); return }
        if (isAmazonHome(pkg, cls)) {
            trace("AMAZON_HOME eventTime=${event.eventTime} active=${active()}")
            if (active()) returnHome("amazon-window")
        } else if (pkg == HomeGuard.AMAZON_PACKAGE || pkg.startsWith("com.amazon.tv.quicksettings") ||
            pkg.startsWith("com.amazon.tv.settings")) {
            // Long-HOME quick settings, Alexa and Settings must always remain reachable.
            if (cls.contains("Activity") || pkg.startsWith("com.amazon.tv.quicksettings")) allowAmazon(30_000)
        } else if (pending && cls.contains("Activity")) {
            cancelTransition()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || HomeGuard.homeOnFireEnabled(this)) return false
        trace("HOME_KEY action=${event.action} repeat=${event.repeatCount} long=${event.isLongPress}")
        if (event.isLongPress || (event.action == KeyEvent.ACTION_UP && event.eventTime - event.downTime >= 500)) {
            allowAmazon(30_000)
        } else if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // Keep the OS sequence intact until strategy A is proven on the physical remote.
            escapeUntil = 0
            if (HomeGuard.foreground) HomeGuard.requestReset()
        }
        return false
    }

    private fun returnHome(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (!active() || pending || now - lastAttempt < 1_000) return
        // Persist before launch: an Activity crash can kill this service's process too.
        val wallNow = System.currentTimeMillis()
        val blockedUntil = failures.getLong("blockedUntil", 0)
        if (wallNow < blockedUntil && blockedUntil - wallNow <= 300_000) {
            HomeGuard.detail = "Aura failed to draw 3x; guard paused 5 min"
            return
        }
        val first = failures.getLong("firstAttempt", 0)
        val count = if (wallNow - first in 0..30_000) failures.getInt("attempts", 0) else 0
        if (count >= 3) {
            failures.edit().putLong("blockedUntil", wallNow + 300_000).commit()
            HomeGuard.detail = "Aura failed to draw 3x; guard paused 5 min"
            cancelTransition()
            trace("FAIL_OPEN durationMs=300000")
            return
        }
        val saved = failures.edit().putLong("firstAttempt", if (count == 0) wallNow else first)
            .putInt("attempts", count + 1).putLong("blockedUntil", 0).commit()
        if (!saved) {
            allowAmazon(120_000)
            HomeGuard.detail = "Launch safety state unavailable; guard paused"
            return
        }
        lastAttempt = now
        pending = true
        HomeGuard.triggerAt = now
        HomeGuard.detail = null
        curtain.show()
        trace("LAUNCH_REQUEST reason=$reason")
        main.postDelayed(timeout, 8_000)
        if (!HomeGuard.launch(this, reset = true)) {
            cancelTransition()
            if (failures.getInt("attempts", 0) >= 3) pauseFailedLaunches()
        }
    }

    private fun pauseFailedLaunches() {
        failures.edit().putLong("blockedUntil", System.currentTimeMillis() + 300_000).commit()
        cancelTransition()
        HomeGuard.detail = "Aura failed to draw 3x; guard paused 5 min"
        trace("FAIL_OPEN durationMs=300000")
    }

    internal fun onAuraDrawn() {
        failures.edit().clear().apply()
        pending = false
        main.removeCallbacks(timeout)
        curtain.hide(fade = true)
    }

    internal fun allowAmazon(durationMs: Long) {
        escapeUntil = SystemClock.uptimeMillis() + durationMs.coerceIn(1_000, 120_000)
        cancelTransition()
        trace("AMAZON_ESCAPE until=$escapeUntil")
    }

    private fun cancelTransition() {
        pending = false
        HomeGuard.triggerAt = 0
        main.removeCallbacks(timeout)
        if (::curtain.isInitialized) curtain.hide()
    }

    override fun onInterrupt() { cancelTransition() }

    override fun onDestroy() {
        cancelTransition()
        contentResolver.unregisterContentObserver(accessibilityObserver)
        runCatching { unregisterReceiver(screenReceiver) }
        if (HomeGuard.service === this) HomeGuard.service = null
        super.onDestroy()
    }

    companion object {
        internal fun trace(message: String) = Log.i("AuraHomeGuard", "uptimeMs=${SystemClock.uptimeMillis()} $message")
        internal fun isAmazonHome(pkg: String, cls: String) = pkg == HomeGuard.AMAZON_PACKAGE &&
            (cls == "com.amazon.tv.launcher.ui.HomeActivity_vNext" || cls == "com.amazon.tv.launcher.ui.HomeActivity")
    }
}
