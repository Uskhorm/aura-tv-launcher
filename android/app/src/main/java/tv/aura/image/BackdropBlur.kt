package tv.aura.image

import android.graphics.Bitmap

/** Three separable sliding-box passes approximate Gaussian blur in O(pixels), independent of radius. */
internal object BackdropBlur {
    fun apply(source: Bitmap, level: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = PixelBlur.apply(pixels, width, height, level)
        return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also {
            it.setPixels(blurred, 0, width, 0, 0, width, height)
        }
    }
}

/** Pure kernel, kept independent of Android so correctness can be checked in host JVM tests. */
internal object PixelBlur {
    fun apply(input: IntArray, width: Int, height: Int, level: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == input.size.toLong())
        var pixels = input.copyOf()
        var scratch = IntArray(pixels.size)
        val radius = when (level) { 1 -> 3; 2 -> 7; else -> 13 }
        repeat(3) {
            pass(pixels, scratch, width, height, radius, horizontal = true)
            val swap = pixels; pixels = scratch; scratch = swap
            pass(pixels, scratch, width, height, radius, horizontal = false)
            val swap2 = pixels; pixels = scratch; scratch = swap2
        }
        return pixels
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val length = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val stride = if (horizontal) 1 else w
        val divisor = radius * 2 + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var red = 0; var green = 0; var blue = 0
            for (i in -radius..radius) {
                val pixel = src[base + i.coerceIn(0, length - 1) * stride]
                red += (pixel ushr 16) and 255; green += (pixel ushr 8) and 255; blue += pixel and 255
            }
            for (x in 0 until length) {
                dst[base + x * stride] = (255 shl 24) or ((red / divisor) shl 16) or
                    ((green / divisor) shl 8) or (blue / divisor)
                val before = src[base + (x - radius).coerceIn(0, length - 1) * stride]
                val after = src[base + (x + radius + 1).coerceIn(0, length - 1) * stride]
                red += ((after ushr 16) and 255) - ((before ushr 16) and 255)
                green += ((after ushr 8) and 255) - ((before ushr 8) and 255)
                blue += (after and 255) - (before and 255)
            }
        }
    }
}
