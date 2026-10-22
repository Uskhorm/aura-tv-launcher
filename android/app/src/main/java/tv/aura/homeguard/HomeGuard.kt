package tv.aura.homeguard

import android.app.Activity
import android.app.Application
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewTreeObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeGuardStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val strategy: String,
    val passiveForHomeOnFire: Boolean,
    /** Trigger to first drawn Aura frame, not physical key latency when HOME is hidden by firmware. */
    val lastLatencyMs: Long?,
    val curtainVisible: Boolean,
    val detail: String?,
)

/** Process-local bridge. The accessibility service must run in the application process. */
object HomeGuard {
    internal const val HOF_PACKAGE = "io.github.toolicious.homeonfire"
    internal const val AMAZON_PACKAGE = "com.amazon.tv.launcher"
    internal var service: AuraHomeService? = null
    internal var foreground = false
    internal var triggerAt = 0L
    internal var latency: Long? = null
    internal var detail: String? = null
    private var app: Application? = null
    private val resets = MutableStateFlow(0L)
    val resetRequests = resets.asStateFlow()

    /** Call once from Application.onCreate. Captures Aura only; never captures another app's screen. */
    fun initialize(application: Application) {
        if (app != null) return
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!isAuraHome(activity)) return
                foreground = true
                val decor = activity.window.decorView
                val observer = object : ViewTreeObserver.OnDrawListener {
                    private var posted = false
                    override fun onDraw() {
                        if (posted) return
                        posted = true
                        decor.post {
                            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(this)
                            if (!foreground) return@post
                            if (triggerAt > 0) {
                                latency = SystemClock.uptimeMillis() - triggerAt
                                AuraHomeService.trace("AURA_DRAWN latencyMs=$latency")
                                triggerAt = 0
                            }
                            service?.onAuraDrawn()
                        }
                    }
                }
                decor.viewTreeObserver.addOnDrawListener(observer)
                decor.invalidate()
            }
            override fun onActivityPaused(activity: Activity) {
                if (!isAuraHome(activity)) return
                foreground = false
                if (service != null) HomeSnapshot.capture(activity)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun isAuraHome(activity: Activity) =
        activity.packageName == "tv.aura.home" && activity.javaClass.simpleName == "MainActivity"

    internal fun requestReset() { resets.value += 1 }

    fun status(): HomeGuardStatus = app?.let { status(it) }
        ?: HomeGuardStatus(false, false, "WINDOW_CURTAIN", false, null, false, "HomeGuard not initialized")

    fun status(context: Context): HomeGuardStatus {
        val components = enabledServices(context)
        val enabled = components.any {
            it == ComponentName(context, AuraHomeService::class.java)
        } && Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        return HomeGuardStatus(enabled, service != null, "WINDOW_CURTAIN", homeOnFireEnabled(context),
            latency, service?.curtainVisible == true, detail)
    }

    internal fun enabledServices(context: Context): List<ComponentName> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it) }

    internal fun homeOnFireEnabled(context: Context) = enabledServices(context).any { it.packageName == HOF_PACKAGE }

    internal fun launch(context: Context, reset: Boolean): Boolean {
        if (homeOnFireEnabled(context)) return false
        if (reset) requestReset()
        return try {
            context.startActivity(Intent(Intent.ACTION_MAIN)
                .setClassName("tv.aura.home", "tv.aura.app.MainActivity")
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            true
        } catch (error: RuntimeException) {
            detail = "Aura launch failed: ${error.javaClass.simpleName}"
            AuraHomeService.trace(detail.orEmpty())
            false
        }
    }

    /** Explicit user escape; call before opening Amazon home or a Settings panel. */
    fun allowAmazonHome(durationMs: Long = 30_000) { service?.allowAmazon(durationMs) }

    /** User-requested power menu. Fire OS may reject it; caller must check the result. */
    fun showPowerDialog(): Boolean {
        val current = service ?: return false
        current.allowAmazon(30_000)
        return current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
    }
}
