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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled landscape photography (Unsplash licence) used for the Top Shelf and the screensaver. */
object Photos {
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

    private val sharp = LruCache<Int, ImageBitmap>(6)
    private val soft = LruCache<Int, ImageBitmap>(12)

    fun peek(i: Int): ImageBitmap? = sharp.get(i)
    fun peekBlur(i: Int): ImageBitmap? = soft.get(i)

    /** Decodes a photo scaled to the screen width. Call off the main thread. */
    fun load(ctx: Context, i: Int): ImageBitmap {
        sharp.get(i)?.let { return it }
        val screenW = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1280)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (screenW < 1920) { inScaled = true; inDensity = 1920; inTargetDensity = screenW }
        }
        val bmp = ctx.assets.open("photos/${all[i].file}.webp").use { BitmapFactory.decodeStream(it, null, opts)!! }
        val img = bmp.asImageBitmap()
        sharp.put(i, img)
        if (soft.get(i) == null) soft.put(i, blur(bmp).asImageBitmap())
        return img
    }

    fun loadBlur(ctx: Context, i: Int): ImageBitmap = soft.get(i) ?: run { load(ctx, i); soft.get(i)!! }

    /** Cheap, good-looking blur: shrink, box-blur three times, let the GPU scale it back up. */
    private fun blur(src: Bitmap): Bitmap {
        val w = 160
        val h = 90
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 3) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, idx: (Int, Int) -> Int) {
            for (l in 0 until lines) {
                var rs = 0; var gs = 0; var bs = 0
                for (k in -r..r) { val c = src[idx(l, k.coerceIn(0, len - 1))]; rs += c shr 16 and 255; gs += c shr 8 and 255; bs += c and 255 }
                val n = 2 * r + 1
                for (i in 0 until len) {
                    dst[idx(l, i)] = (0xFF shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                    val add = src[idx(l, (i + r + 1).coerceAtMost(len - 1))]
                    val sub = src[idx(l, (i - r).coerceAtLeast(0))]
                    rs += (add shr 16 and 255) - (sub shr 16 and 255)
                    gs += (add shr 8 and 255) - (sub shr 8 and 255)
                    bs += (add and 255) - (sub and 255)
                }
            }
        }
        pass(px, tmp, w, h) { l, i -> l * w + i }
        pass(tmp, px, h, w) { l, i -> i * w + l }
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
