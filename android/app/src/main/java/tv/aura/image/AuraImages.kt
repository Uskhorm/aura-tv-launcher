package tv.aura.image

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import tv.aura.core.AuraPalette
import tv.aura.core.ImagePipeline
import kotlin.math.ceil

/** Application-scoped API-28 pipeline. Calls are safe from Main; all work runs on IO. */
class AuraImages(context: Context) : ImagePipeline, ComponentCallbacks2, AutoCloseable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetches = Semaphore(3)
    private val decodes = Semaphore(2)
    private val bitmapLocks = Array(64) { Mutex() }
    private val blurLocks = Array(16) { Mutex() }
    private val paletteLocks = Array(16) { Mutex() }
    private val disk = ImageDiskCache(File(app.cacheDir, "img"))
    private val missingMaxres = LruCache<String, Boolean>(128)
    private val palettes = LruCache<String, AuraPalette>(64)
    private val pool = ScratchPool()
    private var prefetchJob: Job? = null
    private var lastBlurKey: String? = null
    val memoryBudgetBytes: Int = run {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Leave room for Compose, two decode workers, app icons and the rest of the process.
        minOf(24L * MIB, manager.memoryClass.toLong() * MIB / 8).coerceAtLeast(4L * MIB).toInt()
    }
    private val memory = object : LruCache<String, Bitmap>(memoryBudgetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        // UI may retain an ImageBitmap after eviction. Never recycle or pool these objects.
    }

    init { app.registerComponentCallbacks(this) }

    override suspend fun bitmap(url: String, w: Int, h: Int, alpha: Boolean): ImageBitmap? {
        original(url, w, h, alpha)?.let { return it }
        if (!isYouTubeMaxres(url)) return null
        for (variant in listOf("sddefault.jpg", "hqdefault.jpg")) {
            val fallback = url.replace(Regex("maxresdefault\\.[^?]+"), variant)
            original(fallback, w, h, alpha)?.let { return it }
        }
        return null
    }

    private suspend fun original(url: String, w: Int, h: Int, alpha: Boolean): ImageBitmap? = safe {
        if (w <= 0 || h <= 0 || url.isBlank()) return@safe null
        if (isMissingYouTubeMaxres(url)) return@safe null
        val width = w.coerceAtMost(1280); val height = h.coerceAtMost(720)
        val key = disk.key("image-v1|$url|$width|$height|$alpha")
        memory.get(key)?.let { return@safe it.asImageBitmap() }
        bitmapLocks[(key.hashCode() and Int.MAX_VALUE) % bitmapLocks.size].withLock {
            memory.get(key)?.let { return@withLock it.asImageBitmap() }
            val file = disk.pin(key)
            try {
                if (!file.exists()) {
                    val fetched = fetches.withPermit { withTimeoutOrNull(12_000) { fetch(url, file); true } }
                    if (fetched != true) return@withLock null
                }
                val image = decodes.withPermit { decode(file, width, height, alpha, url) }
                if (image == null) disk.remove(file) else remember(key, image)
                image?.asImageBitmap()
            } finally { disk.release(key) }
        }
    }

    override suspend fun blurred(url: String, level: Int): ImageBitmap? = safe {
        val strength = level.coerceIn(1, 3)
        val key = disk.key("blur-v1|$url|$strength|320|180")
        memory.get(key)?.let { return@safe it.asImageBitmap() }
        blurLocks[(key.hashCode() and Int.MAX_VALUE) % blurLocks.size].withLock {
            memory.get(key)?.let { return@withLock it.asImageBitmap() }
            val file = disk.pin(key)
            try {
                if (file.exists()) {
                    val cached = decodes.withPermit { decode(file, 320, 180, false, "") }
                    if (cached != null) { remember(key, cached, blurred = true); return@withLock cached.asImageBitmap() }
                    disk.remove(file)
                }
                val source = bitmap(url, 320, 180)?.asAndroidBitmap() ?: return@withLock null
                val result = decodes.withPermit {
                    currentCoroutineContext().ensureActive()
                    val started = SystemClock.elapsedRealtimeNanos()
                    BackdropBlur.apply(source, strength).also {
                        Log.d(TAG, "blur level=$strength size=${source.width}x${source.height} cpuMs=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0}")
                    }
                }
                try { writeJpeg(file, result) } catch (_: java.io.IOException) {
                    Log.d(TAG, "Blur disk cache unavailable; retaining memory result")
                }
                remember(key, result, blurred = true)
                result.asImageBitmap()
            } finally { disk.release(key) }
        }
    }

    override suspend fun palette(url: String): AuraPalette? = safe {
        palettes.get(url)?.let { return@safe it }
        paletteLocks[(url.hashCode() and Int.MAX_VALUE) % paletteLocks.size].withLock {
            palettes.get(url)?.let { return@withLock it }
            val key = disk.key("palette-v1|$url")
            val file = disk.pin(key)
            try {
                if (file.exists()) {
                    try {
                        val json = JSONObject(file.readText())
                        return@withLock AuraPalette(json.getInt("d"), json.getInt("v"), json.getInt("m"), json.getInt("o"))
                            .also { palettes.put(url, it) }
                    } catch (_: Exception) { disk.remove(file) }
                }
                val thumb = bitmap(url, 96, 54)?.asAndroidBitmap() ?: return@withLock null
                val colors = decodes.withPermit { Palette.from(thumb).maximumColorCount(12).generate() }
                val dominant = colors.getDominantColor(0xff202b38.toInt())
                val result = AuraPalette(dominant, colors.getVibrantColor(dominant), colors.getMutedColor(dominant), 0xfff5f7fa.toInt())
                val temp = disk.temporary()
                try {
                    temp.writeText(JSONObject().put("d", result.dominant).put("v", result.vibrant).put("m", result.muted).put("o", result.onDark).toString())
                    disk.publish(temp, file)
                } finally { temp.delete() }
                palettes.put(url, result)
                result
            } finally { disk.release(key) }
        }
    }

    /** List order is priority order. Each call cancels the obsolete focus batch. */
    @Synchronized override fun prefetch(urls: List<String>, w: Int, h: Int) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            // Sequential requests let visible requests take the other fetch slots.
            for (url in urls.distinct().take(12)) {
                currentCoroutineContext().ensureActive()
                bitmap(url, w, h)
            }
        }
    }

    fun isMissingYouTubeMaxres(url: String): Boolean = missingMaxres.get(url) == true

    override fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            synchronized(this) { prefetchJob?.cancel() }
            memory.evictAll(); palettes.evictAll(); pool.clear()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            memory.trimToSize(memoryBudgetBytes / 2); pool.clear()
        }
    }
    override fun onTrimMemory(level: Int) = trimMemory(level)
    override fun onLowMemory() = trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun close() { scope.cancel(); app.unregisterComponentCallbacks(this); memory.evictAll(); pool.clear() }

    private suspend fun fetch(url: String, destination: File) {
        val parsed = URL(url)
        require(parsed.protocol == "https" || parsed.protocol == "http")
        val connection = parsed.openConnection() as HttpURLConnection
        val temp = disk.temporary()
        try {
            connection.connectTimeout = 4_000; connection.readTimeout = 4_000
            connection.setRequestProperty("User-Agent", "AuraTV/1.0")
            val status = connection.responseCode
            if (status == 404 && isYouTubeMaxres(url)) missingMaxres.put(url, true)
            require(status == 200) { "HTTP $status" }
            require(connection.contentLengthLong <= MAX_DOWNLOAD) { "Image too large" }
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_DOWNLOAD) { "Image too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            disk.publish(temp, destination)
        } finally { connection.disconnect(); temp.delete() }
    }

    private fun decode(file: File, w: Int, h: Int, alpha: Boolean, url: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > 32768 || bounds.outHeight > 32768 || bounds.outWidth.toLong() * bounds.outHeight > 268435456L) return null
        if (isYouTubePlaceholder(url, bounds.outWidth, bounds.outHeight)) {
            missingMaxres.put(url, true)
            return null
        }
        var sample = 1
        // Round UP: even the intermediate decoded image fits the requested bounds.
        while (ceil(bounds.outWidth.toDouble() / sample) > w || ceil(bounds.outHeight.toDouble() / sample) > h) sample *= 2
        val config = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = config; inMutable = true; inScaled = false
            inBitmap = pool.take(ceil(bounds.outWidth.toDouble() / sample).toInt() * ceil(bounds.outHeight.toDouble() / sample).toInt() * if (alpha) 4 else 2, config)
        }
        val reused = options.inBitmap
        var decoded = try { BitmapFactory.decodeFile(file.path, options) } catch (_: IllegalArgumentException) {
            options.inBitmap = null
            reused?.let { pool.offer(it) }
            BitmapFactory.decodeFile(file.path, options)
        } ?: return null
        if (decoded.width > w || decoded.height > h) {
            val scale = minOf(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
            val fitted = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            if (fitted !== decoded) pool.offer(decoded)
            decoded = fitted
        }
        // Small mutable decodes become immutable UI images; their private backing can be reused.
        if (decoded.allocationByteCount <= MIB) {
            val published = decoded.copy(config, false) ?: return decoded
            pool.offer(decoded)
            return published
        }
        if (!alpha && decoded.config != Bitmap.Config.RGB_565) {
            val opaque = decoded.copy(Bitmap.Config.RGB_565, false) ?: return decoded
            decoded.recycle()
            return opaque
        }
        return decoded
    }

    private fun remember(key: String, bitmap: Bitmap, blurred: Boolean = false) = synchronized(memory) {
        if (blurred) {
            lastBlurKey?.takeIf { it != key }?.let { memory.remove(it) }
            lastBlurKey = key
        }
        // D7: at most two sharp full-screen images. UI must release old references after transitions.
        if (bitmap.width * bitmap.height >= 640 * 360) {
            val large = memory.snapshot().filterValues { it.width * it.height >= 640 * 360 }
            large.keys.take((large.size - 1).coerceAtLeast(0)).forEach { memory.remove(it) }
        }
        memory.put(key, bitmap)
    }

    private fun writeJpeg(file: File, bitmap: Bitmap) {
        val temp = disk.temporary()
        try {
            temp.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            disk.publish(temp, file)
        } finally { temp.delete() }
    }

    private suspend fun <T> safe(block: suspend () -> T?): T? = withContext(Dispatchers.IO) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Log.d(TAG, "Image unavailable: ${failure.javaClass.simpleName}"); null }
    }

    companion object {
        private const val TAG = "AuraImages"
        private const val MIB = 1024 * 1024
        private const val MAX_DOWNLOAD = 12L * MIB
        private fun isYouTubeMaxres(url: String): Boolean = try {
            val parsed = URL(url)
            (parsed.host == "i.ytimg.com" || parsed.host.endsWith(".ytimg.com")) &&
                parsed.path.contains("maxresdefault.")
        } catch (_: Exception) { false }
        fun isYouTubePlaceholder(url: String, width: Int, height: Int): Boolean =
            width == 120 && height == 90 && isYouTubeMaxres(url)
    }
}

/** Only unexposed intermediates enter this pool. Published ImageBitmaps are never reusable. */
private class ScratchPool {
    private val entries = ArrayDeque<Bitmap>()
    @Synchronized fun take(bytes: Int, config: Bitmap.Config): Bitmap? {
        val candidate = entries.firstOrNull { !it.isRecycled && it.config == config && it.allocationByteCount >= bytes } ?: return null
        entries.remove(candidate)
        return candidate
    }
    @Synchronized fun offer(bitmap: Bitmap) {
        if (!bitmap.isMutable || bitmap.allocationByteCount > 1024 * 1024) { bitmap.recycle(); return }
        while (entries.sumOf { it.allocationByteCount } + bitmap.allocationByteCount > 1024 * 1024) entries.removeFirst().recycle()
        entries.addLast(bitmap)
    }
    @Synchronized fun clear() { entries.forEach { it.recycle() }; entries.clear() }
}
