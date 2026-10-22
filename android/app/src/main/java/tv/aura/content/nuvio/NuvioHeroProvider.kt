package tv.aura.content.nuvio

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.aura.core.AuraConfig
import tv.aura.core.HeroItem
import tv.aura.core.HeroProvider
import tv.aura.core.LaunchTarget

class NuvioHeroProvider : HeroProvider {
    override val id = "nuvio"
    override val appPackages = setOf(PACKAGE)
    override val refreshIntervalMs = MovieJsonDiskCache.TTL_MS

    override suspend fun load(ctx: Context, config: AuraConfig): List<HeroItem> = withContext(Dispatchers.IO) {
        try {
            val result = withTimeoutOrNull(6_800) {
                coroutineScope {
                    val movie = async { MovieCatalogStore.catalog(ctx, "nuvio-movie", "$BASE/catalog/movie/top.json", "movie") }
                    val series = async { MovieCatalogStore.catalog(ctx, "nuvio-series", "$BASE/catalog/series/top.json", "series") }
                    movie.await() to series.await()
                }
            }
            val movies = result?.first.orEmpty().ifEmpty { MovieCatalogStore.cached(ctx, "nuvio-movie", "movie") }
                .ifEmpty { MovieSeeds.nuvio.filter { it.type == "movie" } }
            val series = result?.second.orEmpty().ifEmpty { MovieCatalogStore.cached(ctx, "nuvio-series", "series") }
                .ifEmpty { MovieSeeds.nuvio.filter { it.type == "series" } }
            heroes(movies, series)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { heroes(MovieSeeds.nuvio.filter { it.type == "movie" }, MovieSeeds.nuvio.filter { it.type == "series" }) }
    }

    private fun heroes(movies: List<MovieMeta>, series: List<MovieMeta>): List<HeroItem> {
        val today = MovieCatalogParser.londonToday()
        return MovieCatalogParser.select(movies, series, today.toEpochDay()).map { meta ->
            MovieCatalogParser.hero(meta, id, PACKAGE, detailTarget(meta), today.year)
        }
    }

    companion object {
        const val PACKAGE = "com.nuvio.tv"
        private const val BASE = "https://v3-cinemeta.strem.io"
        fun detailTarget(meta: MovieMeta): LaunchTarget = LaunchTarget.Deep(
            pkg = PACKAGE, data = "nuvio://meta/${meta.type}/${meta.id}", fallback = LaunchTarget.App(PACKAGE),
        )
    }
}
