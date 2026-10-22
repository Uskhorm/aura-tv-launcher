package tv.aura.content.nuvio

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MovieJsonDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun expiredJsonRemainsAvailableOfflineAndReplacementIsComplete() {
        val dir = temporary.newFolder("cache")
        val cache = MovieJsonDiskCache(dir)
        cache.write("movie", "old")
        val file = File(dir, "movie.json")
        val old = System.currentTimeMillis() - MovieJsonDiskCache.TTL_MS - 5_000
        assertTrue(file.setLastModified(old))
        assertNull(cache.read("movie", System.currentTimeMillis(), freshOnly = true))
        assertEquals("old", cache.read("movie", System.currentTimeMillis()))
        cache.write("movie", "new complete payload")
        assertEquals("new complete payload", cache.read("movie", System.currentTimeMillis(), freshOnly = true))
        assertEquals(listOf("movie.json"), dir.listFiles()!!.map { it.name })
    }

    @Test fun oversizedResponsesCannotEraseLastGoodJsonAndPathTraversalCannotReadOutsideCache() {
        val cache = MovieJsonDiskCache(temporary.newFolder("cache"))
        cache.write("movie", "good")
        cache.write("movie", "x".repeat(MovieJsonDiskCache.MAX_BYTES + 1))
        assertEquals("good", cache.read("movie", System.currentTimeMillis()))
        assertNull(cache.read("../escape", System.currentTimeMillis()))
    }

    @Test fun missingCacheAndFileWhereDirectoryShouldBeAreHarmless() {
        val file = temporary.newFile("not-directory")
        val cache = MovieJsonDiskCache(file)
        cache.write("movie", "payload")
        assertNull(cache.read("movie", System.currentTimeMillis()))
    }

    @Test fun detailCacheHasAnUpperFileCountBound() {
        val dir = temporary.newFolder("cache")
        val cache = MovieJsonDiskCache(dir)
        repeat(105) { cache.write("meta-$it", "{}") }
        assertTrue(dir.listFiles()!!.size <= 100)
    }
}
