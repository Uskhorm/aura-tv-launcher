package tv.aura.content.youtube

import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Header-only image decode; no full bitmap allocation. */
object YouTubeMaxres {
    private val checked = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    fun isRealMaxres(width: Int, height: Int): Boolean =
        width >= 1280 && height >= 720 && kotlin.math.abs(width.toDouble() / height - 16.0 / 9) < 0.03
    fun hasRealMaxres(videoId: String): Boolean {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        val now = System.currentTimeMillis()
        checked[videoId]?.let { if (now - it.first in 0 until 3_600_000) return it.second }
        val connection = URL("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.instanceFollowRedirects = false
        try {
            val valid = connection.responseCode == 200 && connection.inputStream.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                isRealMaxres(options.outWidth, options.outHeight)
            }
            if (checked.size > 500) checked.clear()
            checked[videoId] = now to valid
            return valid
        } finally { connection.disconnect() }
    }
}
