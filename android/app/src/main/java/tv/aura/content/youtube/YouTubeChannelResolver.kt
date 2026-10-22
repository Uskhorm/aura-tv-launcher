package tv.aura.content.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.aura.core.YtChannel
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Phone companion API. Invalid input, private/deleted channels and network failures return null. */
suspend fun resolveChannel(input: String): YtChannel? = YouTubeChannelResolver().resolveChannel(input)

class YouTubeChannelResolver {
    suspend fun resolveChannel(input: String): YtChannel? = withContext(Dispatchers.IO) {
        try {
            val path = channelPath(input) ?: return@withContext null
            val cached = resolved[path]
            if (cached != null && System.currentTimeMillis() - cached.first in 0 until 86_400_000) return@withContext cached.second
            val id = path.removePrefix("/channel/").takeIf { YouTubeFeedParser.channelIdPattern.matches(it) }
                ?: channelIdFromHtml(ContentHttp.get("https://www.youtube.com$path", 2 * 1024 * 1024))
                ?: return@withContext null
            val xml = ContentHttp.get("https://www.youtube.com/feeds/videos.xml?channel_id=$id")
            val videos = YouTubeFeedParser.parse(xml)
            val name = videos.firstOrNull()?.channelName?.takeIf { it.isNotBlank() }
                ?: feedTitle(xml) ?: return@withContext null
            YtChannel(id, name).also {
                if (resolved.size > 100) resolved.clear()
                resolved[path] = System.currentTimeMillis() to it
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }

    companion object {
        private val resolved = ConcurrentHashMap<String, Pair<Long, YtChannel>>()

        /** Allow only YouTube channel paths; no arbitrary URL fetching from LAN input. */
        fun channelPath(input: String): String? {
            val text = input.trim().takeIf { it.length in 1..512 } ?: return null
            if (YouTubeFeedParser.channelIdPattern.matches(text)) return "/channel/$text"
            val path = if (text.startsWith("@")) "/$text" else {
                val uri = runCatching { URI(if (text.startsWith("youtube.com/") || text.startsWith("www.youtube.com/")) "https://$text" else text) }.getOrNull() ?: return null
                if (uri.scheme != "https" && uri.scheme != "http") return null
                if (uri.host?.lowercase() !in setOf("youtube.com", "www.youtube.com", "m.youtube.com") || uri.userInfo != null || uri.port != -1) return null
                uri.path ?: return null
            }
            val parts = path.trim('/').split('/')
            return when {
                parts[0].startsWith("@") && parts[0].length in 2..101 && parts.size <= 2 && (parts.size == 1 || parts[1] in setOf("videos", "streams", "featured", "shorts")) -> {
                    val handle = parts[0].drop(1)
                    if (handle.any { !it.isLetterOrDigit() && Character.getType(it) !in setOf(
                            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()
                        ) && it !in "_.-·" }) null
                    else "/@" + URLEncoder.encode(handle, "UTF-8").replace("+", "%20")
                }
                parts.size in 2..3 && parts[0] == "channel" && YouTubeFeedParser.channelIdPattern.matches(parts[1]) &&
                    (parts.size == 2 || parts[2] in setOf("videos", "streams", "featured", "shorts")) -> "/channel/${parts[1]}"
                parts.size == 2 && parts[0] in setOf("c", "user") && Regex("[A-Za-z0-9_.-]{1,100}").matches(parts[1]) -> "/${parts[0]}/${parts[1]}"
                else -> null
            }
        }

        fun channelIdFromHtml(html: String): String? {
            // Owner-level identifiers take priority over channelId values on recommended videos.
            val patterns = listOf(
                Regex("\"externalId\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{22})\""),
                Regex("<meta[^>]+itemprop=[\"']channelId[\"'][^>]+content=[\"'](UC[A-Za-z0-9_-]{22})[\"']"),
                Regex("<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']https://www.youtube.com/channel/(UC[A-Za-z0-9_-]{22})"),
                Regex("\"channelId\"\\s*:\\s*\"(UC[A-Za-z0-9_-]{22})\""),
            )
            return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
        }

        private fun feedTitle(xml: String): String? {
            val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "title" && parser.depth == 2) return parser.nextText().takeIf { it.isNotBlank() }
                parser.next()
            }
            return null
        }
    }
}
