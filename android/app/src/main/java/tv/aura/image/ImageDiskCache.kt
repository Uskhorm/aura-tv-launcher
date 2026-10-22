package tv.aura.image

import java.io.File
import java.security.MessageDigest

/** Atomic entries; active readers/writers are pinned against LRU eviction. */
internal class ImageDiskCache(private val root: File, private val maxBytes: Long = 150L * 1024 * 1024) {
    private val active = mutableMapOf<String, Int>()

    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun pin(key: String): File = synchronized(this) {
        root.mkdirs()
        active[key] = (active[key] ?: 0) + 1
        File(root, key).also { if (it.exists()) it.setLastModified(System.currentTimeMillis()) }
    }

    fun release(key: String) = synchronized(this) {
        val count = (active[key] ?: 1) - 1
        if (count == 0) active.remove(key) else active[key] = count
        prune()
    }

    fun temporary(): File {
        root.mkdirs()
        return File.createTempFile("incoming-", ".tmp", root)
    }

    fun publish(temp: File, target: File) = synchronized(this) {
        check(temp.renameTo(target)) { "Cannot publish image cache entry" }
    }

    private fun prune() {
        val files = root.listFiles()?.toList() ?: return
        // Recover abandoned writes after process death; live transfers have a 12s deadline + 4s socket bound.
        val staleBefore = System.currentTimeMillis() - 60_000
        files.filter { it.name.endsWith(".tmp") && it.lastModified() < staleBefore }.forEach { it.delete() }
        val entries = files.filter { !it.name.endsWith(".tmp") }
        var size = entries.sumOf { it.length() }
        if (size <= maxBytes) return
        for (entry in entries.sortedBy { it.lastModified() }) {
            if (entry.name in active) continue
            val bytes = entry.length()
            if (entry.delete()) size -= bytes
            if (size <= maxBytes) break
        }
    }
}
