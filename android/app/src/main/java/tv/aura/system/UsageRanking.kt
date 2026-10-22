package tv.aura.system

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlin.math.exp
import kotlin.math.ln

internal data class UsageSnapshot(val ranked: List<String> = emptyList(), val recent: List<String> = emptyList(),
    val permitted: Boolean = false, val lastUsedAt: Long = 0L)

/** No binder/UsageStats work on the UI thread. Frequency counts foreground events, not duration alone. */
internal class UsageRanking(private val context: Context) {
    @Suppress("DEPRECATION")
    fun read(launchable: Set<String>): UsageSnapshot {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return UsageSnapshot()
        if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) !=
            AppOpsManager.MODE_ALLOWED) return UsageSnapshot()
        return runCatching {
            val manager = context.getSystemService(UsageStatsManager::class.java) ?: return UsageSnapshot()
            val now = System.currentTimeMillis()
            val since = now - 30L * 24 * 60 * 60 * 1000
            val last = mutableMapOf<String, Long>()
            val duration = mutableMapOf<String, Long>()
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, now).orEmpty().forEach { stat ->
                if (stat.packageName in launchable && stat.lastTimeUsed in (since + 1)..now) {
                    last[stat.packageName] = maxOf(last[stat.packageName] ?: 0, stat.lastTimeUsed)
                    duration[stat.packageName] = (duration[stat.packageName] ?: 0) + stat.totalTimeInForeground
                }
            }
            val counts = mutableMapOf<String, Int>()
            val events = manager.queryEvents(since, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName in launchable) {
                    counts[event.packageName] = (counts[event.packageName] ?: 0) + 1
                    last[event.packageName] = maxOf(last[event.packageName] ?: 0, event.timeStamp)
                }
            }
            val scored = last.keys.sortedWith(compareByDescending<String> { pkg ->
                val ageDays = ((now - last.getValue(pkg)).coerceAtLeast(0)).toDouble() / 86_400_000
                6.0 * exp(-ageDays / 3.0) + 2.0 * ln(1.0 + (counts[pkg] ?: 0)) +
                    0.35 * ln(1.0 + (duration[pkg] ?: 0) / 60_000.0)
            }.thenBy { it })
            UsageSnapshot(scored, last.keys.sortedByDescending { last[it] }, true, last.values.maxOrNull() ?: 0L)
        }.getOrElse { UsageSnapshot() }
    }
}
