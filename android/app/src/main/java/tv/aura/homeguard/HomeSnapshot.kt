package tv.aura.homeguard

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/** One 480x270 RGB565 in-memory frame (~253 KiB), persisted off the main thread. */
internal object HomeSnapshot {
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "AuraHomeSnapshot").apply { isDaemon = true } }
    @Volatile var frame: Bitmap? = null
        private set
    private fun file(context: Context) = AtomicFile(File(context.cacheDir, "homeguard-last-frame.jpg"))

    fun preload(context: Context) {
        io.execute {
            if (frame != null) return@execute
            runCatching {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val loaded = file(context).openRead().use { BitmapFactory.decodeStream(it, null, options) }
                // Only a validated small app-owned snapshot is allowed to become an overlay.
                if (loaded != null && loaded.width <= 480 && loaded.height <= 270 && frame == null) frame = loaded
            }
        }
    }

    fun capture(activity: Activity) {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = runCatching {
            Bitmap.createBitmap(480, 270, Bitmap.Config.RGB_565).also {
                val canvas = Canvas(it)
                canvas.scale(480f / view.width, 270f / view.height)
                view.draw(canvas)
            }
        }.getOrNull() ?: return
        frame = bitmap
        io.execute {
            val target = file(activity.applicationContext)
            var output: java.io.FileOutputStream? = null
            try {
                output = target.startWrite()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                target.finishWrite(output)
            } catch (_: Exception) { output?.let { target.failWrite(it) } }
        }
    }
}
