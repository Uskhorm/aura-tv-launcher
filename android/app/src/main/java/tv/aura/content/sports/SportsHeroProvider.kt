package tv.aura.content.sports

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.aura.content.youtube.ContentHttp
import tv.aura.content.youtube.FeedDiskCache
import tv.aura.core.AuraConfig
import tv.aura.core.HeroItem
import tv.aura.core.HeroProvider
import tv.aura.core.LaunchTarget
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Public TheSportsDB V1 schedules. This is not a live-score API or a stream directory. */
class SportsHeroProvider : HeroProvider {
    override val id = "sports"
    override val appPackages = setOf(PACKAGE)
    override val refreshIntervalMs = 15 * 60_000L
    private val attempted = ConcurrentHashMap<String, Long>()

    override suspend fun load(ctx: Context, config: AuraConfig): List<HeroItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val day = Instant.ofEpochMilli(now).atZone(SportsFeedParser.ukZone).toLocalDate().toString()
            val focus = config.sportsFocus.filter { it.lowercase() in setOf("football", "f1") }
                .ifEmpty { listOf("football", "f1") }
            val sources = buildMap {
                if (focus.any { it.equals("football", true) }) {
                    put("epl-$day", "eventsday.php?d=$day&l=4328")
                    put("ucl-$day", "eventsday.php?d=$day&l=4480")
                    put("epl", "eventsnextleague.php?id=4328")
                    put("ucl", "eventsnextleague.php?id=4480")
                }
                if (focus.any { it.equals("f1", true) }) put("f1", "eventsnextleague.php?id=4370")
            }
            val cache = FeedDiskCache(File(ctx.cacheDir, "aura-sports-v1"))
            val events = ConcurrentHashMap<String, List<SportsFixture>>()
            val freshSources = ConcurrentHashMap.newKeySet<String>()
            sources.keys.forEach { key ->
                safely { cache.read("$key.json")?.let(SportsFeedParser::parse) }?.let { events[key] = it }
                if (cache.fresh("$key.json", now, 5 * 60_000L)) freshSources += key
            }
            val parallel = Semaphore(4)
            withTimeoutOrNull(3_500) {
                supervisorScope {
                    sources.forEach { (key, path) -> launch {
                        parallel.withPermit {
                            if (cache.fresh("$key.json", now, refreshIntervalMs) || now - (attempted[key] ?: 0) in 0 until 60_000) return@withPermit
                            attempted[key] = now
                            safely {
                                val json = ContentHttp.get("https://www.thesportsdb.com/api/v1/json/123/$path")
                                val parsed = SportsFeedParser.parse(json)
                                events[key] = parsed
                                freshSources += key
                                cache.write("$key.json", json)
                            }
                        }
                    } }
                }
            }
            cache.prune(sources.keys.map { "$it.json" }.toSet(), 2 * 24 * 60 * 60_000L)
            val selected = SportsFeedParser.select(events.values.flatten(), focus, now)
            if (selected.isEmpty()) return@withContext emptyList()
            val recentlyVerified = freshSources.flatMap { events[it].orEmpty() }.map { it.id }.toSet()
            val artwork = ConcurrentHashMap.newKeySet<String>()
            withTimeoutOrNull(3_000) {
                supervisorScope {
                    selected.filter { it.backdropUrl != null && tv.aura.content.youtube.YouTubeFeedParser.isLatinTitle(it.title) }
                        .forEach { event -> launch {
                            parallel.withPermit {
                                if (safely { SportsArtwork.isPremium(event.backdropUrl!!) } == true) artwork += event.id
                            }
                        } }
                }
            }
            selected.filter { it.id in artwork }.map { event -> HeroItem(
                id = "sports:${event.id}", appPackage = PACKAGE, title = event.title,
                subtitle = SportsFeedParser.badge(event, now, event.id in recentlyVerified),
                backdropUrl = event.backdropUrl,
                badge = null,
                accentArgb = 0xFF53D5A4.toInt(), launch = LaunchTarget.App(PACKAGE),
            ) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyList() }
    }

    private inline fun <T> safely(block: () -> T): T? = try { block() }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }

    companion object { const val PACKAGE = "app.aksports.live" }
}
