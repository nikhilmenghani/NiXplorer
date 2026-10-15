package com.nixplorer.screen.main.tab.apps.provider

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import com.nixplorer.screen.storage.hasStorageUsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AppUsageSnapshot(
    val hasAccess: Boolean,
    val available: Boolean,
    val lastOpened: Map<String, Long> = emptyMap()
)

// Usage changes independently of installed-package metadata; do not invalidate the fast app cache.
private val usageCache = InvalidatingCache<AppUsageSnapshot>()
internal suspend fun loadAppUsage(context: Context): AppUsageSnapshot = withContext(Dispatchers.IO) {
    if (!context.hasStorageUsageAccess()) return@withContext AppUsageSnapshot(false, false)
    usageCache.get((SystemClock.elapsedRealtime() / 60_000).toString()) {
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(UsageStatsManager::class.java)
        // Android retains a limited usage history. Missing entries mean no recorded use, not "never used".
        val stats = manager.queryAndAggregateUsageStats(now - 365L * 24 * 60 * 60 * 1000, now)
        AppUsageSnapshot(true, true, stats.mapValues { it.value.lastTimeUsed }.filterValues { it > 0 })
    }
}
