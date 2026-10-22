package tv.aura.content.legacy

import tv.aura.home.R
import tv.aura.app.*
import tv.aura.design.*
import tv.aura.system.*
import tv.aura.ui.hero.*
import tv.aura.ui.dock.*
import tv.aura.ui.control.*
import tv.aura.ui.settings.*
import tv.aura.ui.status.*
import tv.aura.ui.ambient.*

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import tv.aura.image.BackdropBlur
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled landscape photography (Unsplash licence) used for the Top Shelf and the screensaver. */
object Photos : ComponentCallbacks2 {
    class Info(val file: String, val caption: String)

    val all = listOf(
        Info("p00", "Sea of Clouds"),
        Info("p01", "Turquoise Lake"),
        Info("p02", "Highland Sunrise"),
        Info("p03", "Canyon Road"),
        Info("p04", "Milky Way over the Peaks"),
        Info("p05", "Ocean at Dusk"),
        Info("p06", "Above the Clouds"),
        Info("p07", "Surf from Above"),
        Info("p08", "Northern Lights"),
        Info("p09", "Earth from Orbit"),
        Info("p10", "City Lights from Space"),
        Info("p11", "Manhattan at Dusk"),
        Info("p12", "Mountain Lake at Sunrise"),
        Info("p13", "Golden Peaks"),
        Info("p14", "Layered Ridges"),
        Info("p15", "Alpine Boathouse"),
        Info("p16", "Lone Tree at Dusk"),
        Info("p17", "Ice Cave"),
    )

    // D7: two sharp images and one small blurred copy.
    private val sharp = LruCache<Int, ImageBitmap>(2)
    private val soft = LruCache<Int, ImageBitmap>(1)
    private var callbacksRegistered = false

    fun peek(i: Int): ImageBitmap? = sharp.get(i)
    fun peekBlur(i: Int): ImageBitmap? = soft.get(i)

    /** Decodes a photo scaled to the screen width. Call off the main thread. */
    @Synchronized fun load(ctx: Context, i: Int): ImageBitmap {
        if (!callbacksRegistered) {
            ctx.applicationContext.registerComponentCallbacks(this)
            callbacksRegistered = true
        }
        sharp.get(i)?.let { return it }
        val path = "photos/${all[i].file}.webp"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (kotlin.math.ceil(bounds.outWidth.toDouble() / sample) > 1280 ||
            kotlin.math.ceil(bounds.outHeight.toDouble() / sample) > 720) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sample
            inScaled = false
        }
        val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts)!! }
        val img = bmp.asImageBitmap()
        sharp.put(i, img)
        if (soft.get(i) == null) soft.put(i, blur(bmp).asImageBitmap())
        return img
    }

    @Synchronized fun loadBlur(ctx: Context, i: Int): ImageBitmap = soft.get(i) ?: run {
        val source = load(ctx, i)
        soft.get(i) ?: blur(source.asAndroidBitmap()).asImageBitmap().also { soft.put(i, it) }
    }

    @Synchronized override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            sharp.evictAll(); soft.evictAll()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            sharp.trimToSize(1); soft.evictAll()
        }
    }
    override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    /** Cheap, good-looking blur: shrink, box-blur three times, let the GPU scale it back up. */
    private fun blur(src: Bitmap): Bitmap {
        val w = 160
        val h = 90
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return try { BackdropBlur.apply(small, 1) } finally { if (small !== src) small.recycle() }
    }
}

@Composable
fun rememberPhoto(i: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val v by produceState(Photos.peek(i), i) { if (value == null) value = withContext(Dispatchers.IO) { Photos.load(ctx, i) } }
    return v
}

@Composable
fun rememberBlurredPhoto(i: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val v by produceState(Photos.peekBlur(i), i) { if (value == null) value = withContext(Dispatchers.IO) { Photos.loadBlur(ctx, i) } }
    return v
}
