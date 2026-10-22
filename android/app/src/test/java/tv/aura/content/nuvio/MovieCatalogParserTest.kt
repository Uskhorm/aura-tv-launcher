package tv.aura.content.nuvio

import org.junit.Assert.*
import org.junit.Test
import tv.aura.core.LaunchTarget

class MovieCatalogParserTest {
    @Test fun sparseUkCatalogueGetsLandscapeAndTransparentLogoWithoutInventingRatings() {
        val items = MovieCatalogParser.parse("""{"metas":[{"id":"tt1190634","name":"The Boys","type":"series","genres":["Action"]}]}""", "movie")
        assertEquals(1, items.size)
        val meta = items.single()
        assertEquals("series", meta.type)
        assertEquals("https://images.metahub.space/background/medium/tt1190634/img", meta.backdrop)
        assertEquals("https://images.metahub.space/logo/medium/tt1190634/img", meta.logo)
        assertNull(meta.rating)
    }

    @Test fun malformedRowsAreSkippedAndDuplicateIdsAreCollapsed() {
        val rows = MovieCatalogParser.parse("""{"metas":[null,{},"bad",{"id":"tmdb:123","name":"TMDB"},{"id":"tt1190634","type":"other","name":"Other"},{"id":"tt1190634","name":"Good"},{"id":"tt1190634","name":"Duplicate"}]}""", "series")
        assertEquals(listOf("Good"), rows.map { it.title })
        assertTrue(MovieCatalogParser.parse("not JSON", "movie").isEmpty())
        assertTrue(MovieCatalogParser.parse("""{"metas":null}""", "movie").isEmpty())
    }

    @Test fun metadataEndpointFillsYearGenresAndRatingButNeverChangesId() {
        val sparse = MovieMeta("tt1190634", "series", "The Boys")
        val detail = MovieCatalogParser.parse("""{"meta":{"id":"tt1190634","type":"series","name":"The Boys","year":"2019–","genre":["Action","Comedy"],"imdbRating":"8.7","description":"A corrupt superhero empire."}}""", "series").single()
        val merged = MovieCatalogParser.enrich(sparse, detail)
        val hero = MovieCatalogParser.hero(merged, "nuvio", NuvioHeroProvider.PACKAGE, NuvioHeroProvider.detailTarget(merged), 2026)
        assertEquals("2019 · Action · ★ 8.7", hero.subtitle)
        assertEquals("A corrupt superhero empire.", hero.description)
        assertEquals("nuvio://meta/series/tt1190634", (hero.launch as LaunchTarget.Deep).data)
        assertEquals(LaunchTarget.App("com.nuvio.tv"), hero.launch.fallback)
        assertEquals(sparse, MovieCatalogParser.enrich(sparse, detail.copy(id = "tt0133093")))
    }

    @Test fun dailyRotationIsStableBalancedUniqueAndHandlesEmptyOrOverlappingFeeds() {
        val movies = (0..20).map { MovieMeta("tt10000$it", "movie", "Movie $it") }
        val series = (0..20).map { MovieMeta("tt20000$it", "series", "Series $it") }
        val day = 20_000L
        val first = MovieCatalogParser.select(movies, series, day)
        assertEquals(first, MovieCatalogParser.select(movies, series, day))
        assertNotEquals(first, MovieCatalogParser.select(movies, series, day + 1))
        assertEquals(5, first.count { it.type == "movie" })
        assertEquals(5, first.count { it.type == "series" })
        assertEquals(10, first.map { it.id }.distinct().size)
        assertEquals(10, MovieCatalogParser.select(movies, emptyList(), day).size)
        assertEquals(10, MovieCatalogParser.select(movies, movies, day).size)
        assertTrue(MovieCatalogParser.select(emptyList(), emptyList(), day).isEmpty())
    }

    @Test fun invalidRatingsAndNonHttpsArtworkAreNotTrusted() {
        val meta = MovieCatalogParser.parse("""{"metas":[{"id":"tt0133093","name":"The Matrix","imdbRating":"99","year":"unknown","logo":"file:///secret","background":"http://example.com/a"}]}""", "movie").single()
        assertNull(meta.rating)
        assertNull(meta.year)
        assertTrue(meta.logo!!.startsWith("https://images.metahub.space/"))
        assertTrue(meta.backdrop!!.startsWith("https://images.metahub.space/"))
    }

    @Test fun bundledOfflineShelvesHaveTenValidItemsAndBothMediaTypes() {
        for (shelf in listOf(MovieSeeds.nuvio, MovieSeeds.prime)) {
            assertEquals(10, shelf.size)
            assertEquals(10, shelf.distinctBy { it.id }.size)
            assertEquals(setOf("movie", "series"), shelf.map { it.type }.toSet())
            assertTrue(shelf.all { it.backdrop != null && it.logo != null })
        }
    }
}
