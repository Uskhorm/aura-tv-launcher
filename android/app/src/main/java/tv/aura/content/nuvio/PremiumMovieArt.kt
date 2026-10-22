package tv.aura.content.nuvio

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Fail closed: catalogue URLs alone never establish premium artwork eligibility. */
internal object PremiumMovieArt {
    private val permits = Semaphore(4)

    suspend fun filter(ctx: Context, items: List<MovieMeta>): List<MovieMeta> {
        val disk = MovieJsonDiskCache(java.io.File(ctx.filesDir, "movie-art-v1"))
        fun key(meta: MovieMeta): String = MessageDigest.getInstance("SHA-256")
            .digest("${meta.backdrop}\n${meta.logo}".toByteArray()).joinToString("") { "%02x".format(it) }
        fun approved(meta: MovieMeta): Boolean = runCatching {
            disk.read(key(meta), System.currentTimeMillis())?.let { JSONObject(it).optBoolean("approved", false) } == true
        }.getOrDefault(false)
        val accepted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        items.filter(::approved).forEach { accepted.add(it.id) }
        withTimeoutOrNull(2_000) {
            coroutineScope {
                items.filterNot(::approved).map { meta -> async {
                    permits.withPermit {
                        val valid = runInterruptible { verify(meta) }
                        if (valid) {
                            disk.write(key(meta), JSONObject().put("approved", true).toString())
                            accepted.add(meta.id)
                        }
                    }
                } }.awaitAll()
            }
        }
        return items.filter { it.id in accepted }
    }

    private fun verify(meta: MovieMeta): Boolean = runCatching {
        val backdrop = bytes(meta.backdrop) ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(backdrop, 0, backdrop.size, bounds)
        if (bounds.outWidth < 1280 || bounds.outHeight < 720) return false
        val logo = bytes(meta.logo) ?: return false
        BitmapFactory.decodeByteArray(logo, 0, logo.size, bounds)
        if (bounds.outWidth !in 100..4096 || bounds.outHeight !in 30..2048) return false
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inSampleSize = 4
        }
        val bitmap = BitmapFactory.decodeByteArray(logo, 0, logo.size, options) ?: return false
        try {
            if (!bitmap.hasAlpha()) return false
            var clear = 0
            var visible = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                if (alpha < 16) clear++
                if (alpha > 128) visible++
            }
            val total = bitmap.width * bitmap.height
            alphaEligible(total, clear, visible)
        } finally { bitmap.recycle() }
    }.getOrDefault(false)

    internal fun alphaEligible(total: Int, clear: Int, visible: Int): Boolean =
        total > 0 && clear > total / 20 && visible > total / 100

    private fun bytes(url: String?): ByteArray? {
        if (url == null || !url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 700
            conn.readTimeout = 700
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200 || conn.contentLength > 2_000_000) return null
            conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val deadline = System.nanoTime() + 1_500_000_000L
                while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                    val count = input.read(buffer)
                    if (count < 0) return output.toByteArray()
                    if (output.size() + count > 2_000_000) return null
                    output.write(buffer, 0, count)
                }
                null
            }
        } finally { conn.disconnect() }
    }
}
