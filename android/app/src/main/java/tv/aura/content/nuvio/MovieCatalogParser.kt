package tv.aura.content.nuvio

import org.json.JSONObject
import tv.aura.core.HeroItem
import tv.aura.core.LaunchTarget
import java.time.LocalDate

/** Immutable metadata shared by the two movie shelves; no Context or network required. */
data class MovieMeta(
    val id: String,
    val type: String,
    val title: String,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val description: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val poster: String? = null,
)

object MovieCatalogParser {
    private val imdb = Regex("tt[0-9]{5,12}")

    /** Bad rows are skipped independently. Only IMDb ids can be used with Metahub/Cinemeta. */
    fun parse(json: String, defaultType: String): List<MovieMeta> = runCatching {
        val root = JSONObject(json)
        val metas = root.optJSONArray("metas")
        if (metas != null) (0 until minOf(metas.length(), 150)).mapNotNull {
            metas.optJSONObject(it)?.let { row -> parseRow(row, defaultType) }
        }.distinctBy { it.id }
        else listOfNotNull(root.optJSONObject("meta")?.let { parseRow(it, defaultType) })
    }.getOrDefault(emptyList())

    private fun parseRow(row: JSONObject, defaultType: String): MovieMeta? {
        val id = text(row, "imdb_id") ?: text(row, "id") ?: return null
        if (!imdb.matches(id)) return null
        val type = text(row, "type") ?: defaultType
        if (type != "movie" && type != "series") return null
        val title = text(row, "name") ?: return null
        val genreArray = row.optJSONArray("genres") ?: row.optJSONArray("genre")
        val genres = if (genreArray != null) (0 until minOf(genreArray.length(), 8))
            .mapNotNull { genreArray.optString(it).takeIf { value -> value.isNotBlank() && value != "null" } }
        else listOfNotNull(text(row, "genre"))
        val rating = text(row, "imdbRating")?.toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }
            ?.let { java.lang.String.format(java.util.Locale.UK, "%.1f", it) }
        return MovieMeta(
            id, type, title.take(180),
            (text(row, "releaseInfo") ?: text(row, "year") ?: text(row, "released"))
                ?.takeIf { it.matches(Regex("[12][0-9]{3}.*")) }?.take(12),
            genres, rating, text(row, "description")?.take(1500),
            image(row, "background") ?: metahub("background", id),
            image(row, "logo") ?: metahub("logo", id),
            image(row, "poster") ?: metahub("poster", id),
        )
    }

    private fun text(row: JSONObject, key: String): String? =
        (row.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun image(row: JSONObject, key: String): String? = text(row, key)?.takeIf {
        runCatching { val uri = java.net.URI(it); uri.scheme == "https" && !uri.host.isNullOrBlank() }.getOrDefault(false)
    }

    fun metahub(kind: String, id: String): String = "https://images.metahub.space/$kind/medium/$id/img"

    /** Balanced, stable for a London calendar day, rotates through the first 40 of each feed. */
    fun select(movies: List<MovieMeta>, series: List<MovieMeta>, epochDay: Long, count: Int = 10): List<MovieMeta> {
        fun rotate(items: List<MovieMeta>): List<MovieMeta> {
            val pool = items.distinctBy { it.id }.take(40)
            if (pool.isEmpty()) return pool
            val start = Math.floorMod(epochDay, pool.size.toLong()).toInt()
            return pool.drop(start) + pool.take(start)
        }
        val a = rotate(movies)
        val b = rotate(series)
        return buildList {
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let(::add)
                b.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.id }.take(count.coerceAtLeast(0))
    }

    fun hero(meta: MovieMeta, provider: String, pkg: String, launch: LaunchTarget, currentYear: Int): HeroItem = HeroItem(
        id = "$provider:${meta.id}", appPackage = pkg, title = meta.title,
        subtitle = listOfNotNull(meta.year?.take(4), meta.genres.firstOrNull(), meta.rating?.let { "★ $it" }).joinToString(" · ")
            .ifBlank { if (provider == "prime") "Prime Video · UK catalogue" else if (meta.type == "series") "Series" else "Movie" },
        description = meta.description, backdropUrl = meta.backdrop, logoUrl = meta.logo, posterUrl = meta.poster,
        badge = if (meta.year?.take(4)?.toIntOrNull() == currentYear) "NEW" else "TRENDING",
        launch = launch,
    )

    fun londonToday(): LocalDate = LocalDate.now(java.time.ZoneId.of("Europe/London"))

    /** Fill sparse catalogue rows with Cinemeta detail fields without losing their original title/art. */
    fun enrich(meta: MovieMeta, detail: MovieMeta?): MovieMeta = if (detail == null || meta.id != detail.id) meta else meta.copy(
        year = detail.year ?: meta.year, genres = detail.genres.ifEmpty { meta.genres },
        rating = detail.rating ?: meta.rating, description = detail.description ?: meta.description,
        backdrop = detail.backdrop ?: meta.backdrop, logo = detail.logo ?: meta.logo,
    )
}
