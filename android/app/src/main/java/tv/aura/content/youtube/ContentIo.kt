package tv.aura.content.youtube

import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small bounded transport shared by the two public-feed providers. Call on Dispatchers.IO. */
internal object ContentHttp {
    suspend fun get(url: String, maxBytes: Int = 512 * 1024): String {
        val started = System.nanoTime()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        // Do not follow arbitrary redirects (especially important for companion-supplied handles).
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 AuraTV/1.0")
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val remainingMs = 8_000 - (System.nanoTime() - started) / 1_000_000
                    check(remainingMs > 0) { "Feed deadline" }
                    connection.readTimeout = remainingMs.coerceAtMost(4_000).toInt().coerceAtLeast(1)
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= maxBytes) { "Response too large" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Atomic last-good payloads; HTTP errors and parse failures never replace them. */
internal class FeedDiskCache(directory: File) {
    private val root = directory.apply { mkdirs() }
    fun read(key: String): String? = runCatching {
        val file = File(root, key)
        if (file.length() > 512 * 1024) return@runCatching null
        AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
    fun fresh(key: String, now: Long, ttl: Long): Boolean {
        val modified = File(root, key).lastModified()
        return modified > 0 && now - modified in 0 until ttl
    }
    fun write(key: String, value: String) {
        runCatching {
            val atomic = AtomicFile(File(root, key))
            val stream = atomic.startWrite()
            try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }
    fun prune(activeKeys: Set<String>, maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { file ->
            if (file.name !in activeKeys && now - file.lastModified() > maxAgeMs) file.delete()
        }
    }
}
