package tv.aura.content.prime

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.aura.content.nuvio.MovieCatalogParser
import tv.aura.content.nuvio.MovieCatalogStore
import tv.aura.content.nuvio.MovieJsonDiskCache
import tv.aura.content.nuvio.MovieMeta
import tv.aura.content.nuvio.MovieSeeds
import tv.aura.core.AuraConfig
import tv.aura.core.HeroItem
import tv.aura.core.HeroProvider

/** Discovery metadata only. GB catalogue can include rent/buy, so no subscription-entitlement claims. */
class PrimeHeroProvider : HeroProvider {
    override val id = "prime"
    override val appPackages = setOf(PrimeLinks.PACKAGE)
    override val refreshIntervalMs = MovieJsonDiskCache.TTL_MS

    override suspend fun load(ctx: Context, config: AuraConfig): List<HeroItem> = withContext(Dispatchers.IO) {
        try {
            val today = MovieCatalogParser.londonToday()
            var selected: List<MovieMeta> = emptyList()
            val enriched = java.util.concurrent.ConcurrentHashMap<String, MovieMeta>()
            withTimeoutOrNull(4_500) {
                coroutineScope {
                    val movie = async { MovieCatalogStore.catalog(ctx, "prime-gb-movie", "$BASE/catalog/movie/$CATALOG.json", "movie") }
                    val series = async { MovieCatalogStore.catalog(ctx, "prime-gb-series", "$BASE/catalog/series/$CATALOG.json", "series") }
                    selected = choose(movie.await(), series.await(), today.toEpochDay())
                    // Sparse regional rows need Cinemeta year/rating/landscape metadata, in parallel.
                    selected = selected.map { meta -> async {
                        MovieCatalogParser.enrich(meta, MovieCatalogStore.detail(ctx, meta)).also { enriched[it.id] = it }
                    } }.awaitAll()
                }
            }
            if (selected.isEmpty()) selected = choose(
                MovieCatalogStore.cached(ctx, "prime-gb-movie", "movie"),
                MovieCatalogStore.cached(ctx, "prime-gb-series", "series"), today.toEpochDay(),
            )
            tv.aura.content.nuvio.PremiumMovieArt.filter(ctx, selected.map { enriched[it.id] ?: it }).map { raw ->
                val meta = enriched[raw.id] ?: raw
                MovieCatalogParser.hero(meta, id, PrimeLinks.PACKAGE, PrimeLinks.target(meta), today.year)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val today = MovieCatalogParser.londonToday()
            tv.aura.content.nuvio.PremiumMovieArt.filter(ctx, MovieSeeds.prime).map { MovieCatalogParser.hero(it, id, PrimeLinks.PACKAGE, PrimeLinks.target(it), today.year) }
        }
    }

    private fun choose(movies: List<MovieMeta>, series: List<MovieMeta>, day: Long) = MovieCatalogParser.select(
        movies.ifEmpty { MovieSeeds.prime.filter { it.type == "movie" } },
        series.ifEmpty { MovieSeeds.prime.filter { it.type == "series" } }, day,
    )

    companion object {
        const val BASE = "https://5cfe2edf73d5-omnicatalogs.baby-beamup.club/GB_en_amp"
        const val CATALOG = "GB_en_jw_tnd_amp"
    }
}
