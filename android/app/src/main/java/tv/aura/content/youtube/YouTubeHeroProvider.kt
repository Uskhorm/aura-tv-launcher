package tv.aura.content.youtube

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.aura.core.AuraConfig
import tv.aura.core.HeroItem
import tv.aura.core.HeroProvider
import tv.aura.core.LaunchTarget
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class YouTubeHeroProvider : HeroProvider {
    override val id = "youtube"
    override val appPackages = setOf(PACKAGE)
    override val refreshIntervalMs = 60_000L
    // Even failed requests get a short backoff so repeated focus changes cannot hammer public feeds.
    private val attempted = ConcurrentHashMap<String, Long>()

    override suspend fun load(ctx: Context, config: AuraConfig): List<HeroItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val channels = config.youtubeChannels.filter { YouTubeFeedParser.channelIdPattern.matches(it.id) }
            .distinctBy { it.id }.take(24)
        try {
            val cache = FeedDiskCache(File(ctx.cacheDir, "aura-youtube-v1"))
            val videos = ConcurrentHashMap<String, List<YouTubeVideo>>()
            // Read all last-good data before attempting any network. Removed channels never leak back in.
            channels.forEach { channel ->
                cache.read("${channel.id}.xml")?.let { xml ->
                    safely { YouTubeFeedParser.parse(xml).filter { it.channelId == channel.id } }?.let { videos[channel.id] = it }
                }
            }
            val parallel = Semaphore(4)
            withTimeoutOrNull(3_500) {
                supervisorScope {
                    channels.forEach { channel -> launch {
                        parallel.withPermit {
                            val key = "${channel.id}.xml"
                            if (cache.fresh(key, now, FEED_TTL) || now - (attempted[channel.id] ?: 0) in 0 until 60_000) return@withPermit
                            attempted[channel.id] = now
                            safely {
                                val xml = ContentHttp.get("https://www.youtube.com/feeds/videos.xml?channel_id=${channel.id}")
                                val parsed = YouTubeFeedParser.parse(xml).filter { it.channelId == channel.id }
                                if (parsed.isNotEmpty()) {
                                    cache.write(key, xml)
                                    videos[channel.id] = parsed
                                }
                            }
                        }
                    } }
                }
            }
            val candidates = videos.values.flatten().filter { YouTubeFeedParser.isLatinTitle(it.title) &&
                YouTubeFeedParser.isLatinTitle(it.channelName) && it.publishedAtMs <= now + 5 * 60_000 }
                .distinctBy { it.id }.sortedByDescending { it.publishedAtMs }.take(32)
            val verified = ConcurrentHashMap.newKeySet<String>()
            withTimeoutOrNull(3_000) {
                supervisorScope {
                    candidates.forEach { video -> launch {
                        parallel.withPermit {
                            if (safely { YouTubeMaxres.hasRealMaxres(video.id) } == true) verified += video.id
                        }
                    } }
                }
            }
            val selected = YouTubeFeedParser.select(candidates.filter { it.id in verified }, now)
            cache.prune(channels.map { "${it.id}.xml" }.toSet())
            if (selected.isEmpty()) return@withContext fallback()
            selected.map { video ->
                HeroItem(
                    id = "yt:${video.id}", appPackage = PACKAGE, title = video.title,
                    subtitle = video.channelName,
                    backdropUrl = "https://i.ytimg.com/vi/${video.id}/maxresdefault.jpg",
                    badge = null,
                    accentArgb = 0xFFFF3434.toInt(),
                    launch = TizenTubeLaunch.forVideo(video.id),
                )
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { fallback() }
    }

    private fun fallback(): List<HeroItem> = emptyList()

    private inline fun <T> safely(block: () -> T): T? = try { block() }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }

    companion object {
        const val PACKAGE = "io.gh.reisxd.tizentube.cobalt"
        private const val FEED_TTL = 15 * 60_000L
    }
}

object TizenTubeLaunch {
    /** Kept as App until sol-8 confirms requested-video playback, not just intent resolution. */
    fun forVideo(videoId: String): LaunchTarget {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        return LaunchTarget.App(YouTubeHeroProvider.PACKAGE)
    }
}
