package tv.aura.content.youtube

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.util.Locale

data class YouTubeVideo(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAtMs: Long,
    val views: Long? = null,
)

/** Pure Atom parser; XmlPullParserFactory permits use with a JVM pull-parser in unit tests. */
object YouTubeFeedParser {
    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val YT = "http://www.youtube.com/xml/schemas/2015"
    private const val MEDIA = "http://search.yahoo.com/mrss/"
    val channelIdPattern = Regex("UC[A-Za-z0-9_-]{22}")
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    fun parse(xml: String): List<YouTubeVideo> {
        require(xml.length <= 512 * 1024)
        require(!xml.contains("<!DOCTYPE", ignoreCase = true))
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val result = mutableListOf<YouTubeVideo>()
        var entryDepth = -1
        var id = ""; var channelId = ""; var channel = ""; var title = ""; var date = ""
        var views: Long? = null
        var feedChannelId = ""; var feedName = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    val ns = parser.namespace
                    if (name == "entry" && ns == ATOM) {
                        entryDepth = parser.depth
                        id = ""; channelId = feedChannelId; channel = feedName; title = ""; date = ""; views = null
                    } else if (entryDepth >= 0) {
                        when {
                            name == "videoId" && ns == YT -> id = parser.nextText()
                            name == "channelId" && ns == YT -> channelId = parser.nextText()
                            name == "title" && ns == ATOM -> title = parser.nextText()
                            name == "published" && ns == ATOM -> date = parser.nextText()
                            name == "name" && ns == ATOM -> channel = parser.nextText()
                            name == "statistics" && ns == MEDIA -> views = parser.getAttributeValue(null, "views")?.toLongOrNull()
                        }
                    } else {
                        when {
                            name == "channelId" && ns == YT -> feedChannelId = parser.nextText()
                            name == "title" && ns == ATOM -> feedName = parser.nextText()
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry" && parser.depth == entryDepth) {
                    val published = runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()
                    if (videoIdPattern.matches(id) && channelIdPattern.matches(channelId) && title.isNotBlank() && published != null) {
                        result += YouTubeVideo(id, channelId, channel, title, published, views?.takeIf { it >= 0 })
                    }
                    entryDepth = -1
                }
            }
            parser.next()
        }
        return result
    }

    fun select(videos: List<YouTubeVideo>, nowMs: Long = System.currentTimeMillis(), limit: Int = 10): List<YouTubeVideo> {
        val counts = mutableMapOf<String, Int>()
        return videos.asSequence().filter { it.publishedAtMs <= nowMs + 5 * 60_000 }
            .distinctBy { it.id }.sortedByDescending { it.publishedAtMs }
            .filter { video ->
                val count = counts.getOrDefault(video.channelId, 0)
                (count < 2).also { if (it) counts[video.channelId] = count + 1 }
            }.take(limit.coerceIn(0, 10)).toList()
    }

    fun age(publishedAtMs: Long, nowMs: Long): String {
        val minutes = ((nowMs - publishedAtMs).coerceAtLeast(0) / 60_000)
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 1440 -> "${minutes / 60} h ago"
            else -> "${minutes / 1440} d ago"
        }
    }

    fun viewCount(count: Long): String = when {
        count >= 1_000_000 -> compact(count / 1_000_000.0) + "M views"
        count >= 1_000 -> compact(count / 1_000.0) + "K views"
        else -> "$count views"
    }
    private fun compact(value: Double): String = if (value >= 10 || value % 1.0 < .05) value.toLong().toString()
        else String.format(Locale.UK, "%.1f", value)

    /** A title is only a candidate, never proof that a broadcast is currently live. */
    fun liveCandidate(title: String): Boolean = Regex("(?i)\\bLIVE\\b|लाइव|सीधा प्रसारण").containsMatchIn(title)
    fun confirmsLive(watchHtml: String): Boolean = Regex(
        "\"liveBroadcastDetails\"\\s*:\\s*\\{[^}]{0,2000}\"isLiveNow\"\\s*:\\s*true"
    ).containsMatchIn(watchHtml)
}
