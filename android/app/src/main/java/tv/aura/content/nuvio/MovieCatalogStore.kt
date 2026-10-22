package tv.aura.content.nuvio

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Bounded JSON only cache. Atomic replacement keeps the last good response after process death. */
class MovieJsonDiskCache(private val directory: File) {
    @Synchronized fun read(key: String, nowMs: Long, freshOnly: Boolean = false): String? = runCatching {
        val file = file(key)
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        val age = nowMs - file.lastModified()
        if (freshOnly && age !in 0 until TTL_MS) return null
        file.readText(Charsets.UTF_8)
    }.getOrNull()

    @Synchronized fun write(key: String, json: String) {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val destination = file(key)
            val temp = File.createTempFile("$key-", ".tmp", directory)
            try {
                temp.writeText(json, Charsets.UTF_8)
                try {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                // A changing daily shelf must not grow the metadata cache indefinitely.
                val files = directory.listFiles { candidate -> candidate.extension == "json" }.orEmpty()
                    .sortedByDescending { it.lastModified() }
                var bytes = 0L
                files.forEachIndexed { index, candidate ->
                    bytes += candidate.length()
                    if (index >= 100 || bytes > 12_000_000) candidate.delete()
                }
            } finally { temp.delete() }
        }
    }

    private fun file(key: String): File {
        require(key.matches(Regex("[a-z0-9_-]{1,64}")))
        return File(directory, "$key.json")
    }

    companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val MAX_BYTES = 1_500_000
    }
}

internal object MovieCatalogStore {
    // A resolver stuck inside DNS cannot spawn unbounded work. No Context retained by this pool.
    private val network = ThreadPoolExecutor(4, 4, 20, TimeUnit.SECONDS, ArrayBlockingQueue(16), { task ->
        Thread(task, "aura-movie-json").apply { isDaemon = true }
    }).apply { allowCoreThreadTimeOut(true) }

    private fun cache(ctx: Context) = MovieJsonDiskCache(File(ctx.filesDir, "movie-hero-v1"))

    fun cached(ctx: Context, key: String, type: String): List<MovieMeta> =
        cache(ctx).read(key, System.currentTimeMillis())?.let { MovieCatalogParser.parse(it, type) }.orEmpty()

    suspend fun catalog(ctx: Context, key: String, url: String, type: String): List<MovieMeta> {
        val disk = cache(ctx)
        val fresh = disk.read(key, System.currentTimeMillis(), freshOnly = true)
            ?.let { MovieCatalogParser.parse(it, type) }.orEmpty()
        if (fresh.isNotEmpty()) return fresh
        val json = fetch(url)
        val parsed = json?.let { MovieCatalogParser.parse(it, type) }.orEmpty()
        if (parsed.isNotEmpty()) {
            disk.write(key, requireNotNull(json))
            return parsed
        }
        return cached(ctx, key, type)
    }

    suspend fun detail(ctx: Context, meta: MovieMeta): MovieMeta? = catalog(
        ctx, "meta-${meta.type}-${meta.id}",
        "https://v3-cinemeta.strem.io/meta/${meta.type}/${meta.id}.json", meta.type,
    ).firstOrNull()

    /** Suspension is independently cancellable: a blocking DNS/connect never holds the hero deadline. */
    private suspend fun fetch(url: String): String? = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val task = FutureTask {
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                connection.set(conn)
                if (!continuation.isActive) return@runCatching null
                conn.connectTimeout = 1_800
                conn.readTimeout = 1_800
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "AuraTV/1.0")
                val stopAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                if (conn.responseCode != 200) return@runCatching null
                if (conn.contentLength > MovieJsonDiskCache.MAX_BYTES) return@runCatching null
                conn.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (continuation.isActive && System.nanoTime() < stopAt) {
                        val count = input.read(buffer)
                        if (count < 0) return@use output.toString("UTF-8")
                        if (output.size() + count > MovieJsonDiskCache.MAX_BYTES) return@use null
                        output.write(buffer, 0, count)
                    }
                    null
                }
            }.getOrNull()
            connection.getAndSet(null)?.disconnect()
            if (continuation.isActive) continuation.resume(result)
        }
        continuation.invokeOnCancellation {
            task.cancel(true)
            connection.getAndSet(null)?.disconnect()
            network.remove(task)
        }
        try { network.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
