package tv.aura.homeguard

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.View

/** Non-focusable, non-touchable and bounded: a failed launch can never trap the remote. */
internal class HomeCurtain(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    val visible get() = view != null

    fun show() {
        if (view != null) return
        val image = object : View(service) {
            private var drawn = false
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (!drawn) {
                    drawn = true
                    AuraHomeService.trace("CURTAIN_DRAWN latencyMs=${if (HomeGuard.triggerAt > 0) SystemClock.uptimeMillis() - HomeGuard.triggerAt else -1}")
                }
            }
        }.apply {
            setBackgroundColor(Color.rgb(8, 12, 20))

            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        ).apply { gravity = Gravity.FILL; title = "Aura home transition" }
        try {
            windows.addView(image, layout)
            view = image
            AuraHomeService.trace("CURTAIN_ATTACHED snapshot=false")
        } catch (error: RuntimeException) {
            HomeGuard.detail = "Curtain unavailable: ${error.javaClass.simpleName}"
        }
    }

    fun hide(fade: Boolean = false) {
        val current = view ?: return
        current.animate().cancel()
        if (fade) {
            current.animate().alpha(0f).setDuration(100).withEndAction { remove(current) }.start()
        } else remove(current)
    }

    private fun remove(current: View) {
        if (view !== current) return
        view = null
        runCatching { windows.removeViewImmediate(current) }
        AuraHomeService.trace("CURTAIN_HIDDEN")
    }
}
