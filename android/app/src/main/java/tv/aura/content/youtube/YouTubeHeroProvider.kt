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
    private val live = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    // Even failed requests get a short backoff so repeated focus changes cannot hammer public feeds.
    private val attempted = ConcurrentHashMap<String, Long>()

    override suspend fun load(ctx: Context, config: AuraConfig): List<HeroItem> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
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
            withTimeoutOrNull(4_500) {
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
            val selected = YouTubeFeedParser.select(videos.values.flatten(), now)
            // At most two cheap checks, run concurrently; RSS's LIVE text is not authoritative.
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            // Spend optional watch-page budget only after a fast feed pass. A stalled channel must
            // not turn a 6-second feed timeout into another multi-second batch of watch requests.
            if (elapsedMs < 4_000) withTimeoutOrNull((5_000 - elapsedMs).coerceAtMost(1_000)) {
                supervisorScope {
                    selected.filter { YouTubeFeedParser.liveCandidate(it.title) }.take(2).forEach { video -> launch {
                        val existing = live[video.id]
                        val ttl = if (existing?.second == true) 120_000 else FEED_TTL
                        if (existing != null && now - existing.first in 0 until ttl) return@launch
                        safely {
                            val html = ContentHttp.get("https://www.youtube.com/watch?v=${video.id}", 2 * 1024 * 1024)
                            live[video.id] = now to YouTubeFeedParser.confirmsLive(html)
                        }
                    } }
                }
            }
            if (live.size > 100) live.keys.retainAll(selected.map { it.id }.toSet())
            cache.prune(channels.map { "${it.id}.xml" }.toSet())
            if (selected.isEmpty()) return@withContext fallback()
            selected.map { video ->
                val isLive = live[video.id]?.let { it.second && now - it.first in 0 until 120_000 } == true
                HeroItem(
                    id = "yt:${video.id}", appPackage = PACKAGE, title = video.title,
                    subtitle = listOfNotNull(video.channelName, YouTubeFeedParser.age(video.publishedAtMs, now), video.views?.let(YouTubeFeedParser::viewCount)).joinToString(" · "),
                    backdropUrl = "https://i.ytimg.com/vi/${video.id}/maxresdefault.jpg",
                    badge = if (isLive) "LIVE" else null,
                    accentArgb = 0xFFFF3434.toInt(),
                    launch = TizenTubeLaunch.forVideo(video.id),
                )
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { fallback() }
    }

    private fun fallback() = listOf(HeroItem(
        id = "yt:home", appPackage = PACKAGE, title = "Your world, on YouTube",
        subtitle = "News · Stories · Your subscriptions", description = "Open YouTube for your latest videos.",
        accentArgb = 0xFFFF3434.toInt(), launch = LaunchTarget.App(PACKAGE),
    ))

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
