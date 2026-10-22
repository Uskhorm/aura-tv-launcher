package tv.aura.image

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ImageDiskCacheRegressionTest {
    private fun inCache(block: (File, ImageDiskCache) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("aura-cache-").toFile()
        try { block(root, ImageDiskCache(root, 10)) } finally { root.deleteRecursively() }
    }
    private fun publish(cache: ImageDiskCache, file: File, bytes: Int) {
        val temp = cache.temporary()
        temp.writeBytes(ByteArray(bytes))
        cache.publish(temp, file)
    }
    @Test fun pinnedRawSurvivesPruneUntilAllReadersRelease() = inCache { _, cache ->
        val shared = cache.pin("raw")
        cache.pin("raw")
        publish(cache, shared, 12)
        cache.release("raw")
        assertTrue(shared.exists())
        cache.release("raw")
        assertFalse(shared.exists())
    }
    @Test fun replacementAccountsNewSizeInsteadOfDoubleCounting() = inCache { _, cache ->
        val a = cache.pin("a")
        publish(cache, a, 6)
        publish(cache, a, 4)
        cache.release("a")
        val b = cache.pin("b")
        publish(cache, b, 6)
        cache.release("b")
        assertTrue(a.exists())
        assertTrue(b.exists())
    }
    @Test fun startupPrunesOldestUnpinnedEntry() {
        val root = kotlin.io.path.createTempDirectory("aura-cache-start-").toFile()
        try {
            val oldest = File(root, "old").apply { writeBytes(ByteArray(7)); setLastModified(1000) }
            val newest = File(root, "new").apply { writeBytes(ByteArray(7)); setLastModified(2000) }
            val cache = ImageDiskCache(root, 10)
            cache.pin("new")
            assertFalse(oldest.exists())
            assertTrue(newest.exists())
            cache.release("new")
        } finally { root.deleteRecursively() }
    }
}
