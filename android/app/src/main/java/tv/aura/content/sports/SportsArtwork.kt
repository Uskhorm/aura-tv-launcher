package tv.aura.content.sports

import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal object SportsArtwork {
    private val checked = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    fun isPremium(url: String): Boolean {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null &&
            (uri.host == "thesportsdb.com" || uri.host?.endsWith(".thesportsdb.com") == true))
        val now = System.currentTimeMillis()
        checked[url]?.let { if (now - it.first in 0 until 3_600_000) return it.second }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.instanceFollowRedirects = false
        try {
            val valid = connection.responseCode == 200 && connection.inputStream.use {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(it, null, bounds)
                bounds.outWidth >= 1280 && bounds.outHeight >= 720 &&
                    kotlin.math.abs(bounds.outWidth.toDouble() / bounds.outHeight - 16.0 / 9) < .1
            }
            if (checked.size > 100) checked.clear()
            checked[url] = now to valid
            return valid
        } finally { connection.disconnect() }
    }
}
