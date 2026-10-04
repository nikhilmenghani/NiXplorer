package com.nixplorer.screen.storage

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.storage.StorageManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class AppStorageEntry(val packageName: String, val name: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long, val measured: Boolean, val system: Boolean) {
    // Android includes cache in dataBytes, so don't add it twice.
    val total: Long get() = appBytes + dataBytes
}
internal fun Context.hasStorageUsageAccess(): Boolean {
    val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
}
internal suspend fun loadAppStorage(context: Context): List<AppStorageEntry> = withContext(Dispatchers.IO) {
    val access = context.hasStorageUsageAccess()
    val manager = context.getSystemService(StorageStatsManager::class.java)
    context.packageManager.getInstalledApplications(0).map { app ->
        currentCoroutineContext().ensureActive()
        val stats = if (access) runCatching {
            manager.queryStatsForPackage(app.storageUuid ?: StorageManager.UUID_DEFAULT, app.packageName, Process.myUserHandle())
        }.getOrNull() else null
        val apkBytes = (listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()).sumOf { File(it).length() }
        AppStorageEntry(app.packageName, app.loadLabel(context.packageManager).toString(), stats?.appBytes ?: apkBytes,
            stats?.dataBytes ?: 0, stats?.cacheBytes ?: 0, stats != null,
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
    }.sortedByDescending { it.total }
}
