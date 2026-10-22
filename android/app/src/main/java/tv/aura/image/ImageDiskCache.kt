package tv.aura.image

import java.io.File
import java.security.MessageDigest

/** Atomic entries; readers/writers are pinned. One startup scan, then incremental byte accounting. */
internal class ImageDiskCache(private val root: File, private val maxBytes: Long = 150L * 1024 * 1024) {
    private data class Entry(val bytes: Long, var touched: Long)
    private val active = mutableMapOf<String, Int>()
    private val entries = LinkedHashMap<String, Entry>(128, .75f, true)
    private val abandoned = mutableListOf<File>()
    private var initialized = false
    private var totalBytes = 0L
    private var lastPrune = 0L

    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun pin(key: String): File = synchronized(this) {
        initialize()
        active[key] = (active[key] ?: 0) + 1
        val file = File(root, key)
        val entry = entries[key] // Access-order LRU, no stat calls on the hot path.
        val now = System.currentTimeMillis()
        if (entry != null && now - entry.touched >= 30_000) {
            file.setLastModified(now)
            entry.touched = now
        }
        file
    }

    fun release(key: String) = synchronized(this) {
        val count = (active[key] ?: 1) - 1
        if (count == 0) active.remove(key) else active[key] = count
        val now = System.currentTimeMillis()
        if (totalBytes > maxBytes || now - lastPrune >= 30_000) prune(now)
    }

    fun temporary(): File = synchronized(this) {
        initialize()
        check(root.isDirectory || root.mkdirs()) { "Cannot open image cache directory" }
        File.createTempFile("incoming-", ".tmp", root)
    }

    fun publish(temp: File, target: File) = synchronized(this) {
        initialize()
        val bytes = temp.length()
        java.nio.file.Files.move(temp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        totalBytes -= entries.remove(target.name)?.bytes ?: 0
        entries[target.name] = Entry(bytes, System.currentTimeMillis())
        totalBytes += bytes
        if (totalBytes > maxBytes) prune(System.currentTimeMillis())
    }

    fun remove(file: File) = synchronized(this) {
        if (file.delete() || !file.exists()) totalBytes -= entries.remove(file.name)?.bytes ?: 0
    }

    private fun initialize() {
        if (initialized) return
        check(root.isDirectory || root.mkdirs()) { "Cannot open image cache directory" }
        val files = root.listFiles()?.filter { it.isFile }.orEmpty()
        abandoned.addAll(files.filter { it.name.endsWith(".tmp") })
        files.filter { !it.name.endsWith(".tmp") }.map { it to it.lastModified() }.sortedBy { it.second }
            .forEach { (file, modified) ->
                val bytes = file.length()
                entries[file.name] = Entry(bytes, modified)
                totalBytes += bytes
            }
        initialized = true
        prune(System.currentTimeMillis())
    }

    private fun prune(now: Long) {
        lastPrune = now
        // Track only temp files found at startup. Never scan or delete active new transfers.
        abandoned.removeAll { (now - it.lastModified() >= 60_000 && it.delete()) || !it.exists() }
        if (totalBytes <= maxBytes) return
        val iterator = entries.iterator()
        while (iterator.hasNext() && totalBytes > maxBytes) {
            val (name, entry) = iterator.next()
            if (name in active) continue
            val file = File(root, name)
            if (file.delete() || !file.exists()) {
                totalBytes -= entry.bytes
                iterator.remove()
            }
        }
    }
}
