package com.nixplorer.screen.main.tab.apps.provider

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.emptyString
import com.nixplorer.common.isNot
import com.nixplorer.screen.main.tab.apps.holder.AppHolder
import java.io.File
import java.util.Date
import kotlinx.coroutines.ensureActive

private object InstalledAppsCache {
    val cache = InvalidatingCache<List<AppHolder>>()
    private var registered = false

    @Synchronized fun observe(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) { cache.invalidate() }
        }
        val packages = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val storage = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(android.content.Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        }
        androidx.core.content.ContextCompat.registerReceiver(appContext, receiver, packages,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(appContext, receiver, storage,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }
}

suspend fun getInstalledApps(context: Context): List<AppHolder> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    InstalledAppsCache.observe(context)
    val locale = context.resources.configuration.locales.toLanguageTags()
    InstalledAppsCache.cache.get(locale) {
        val packageManager = context.packageManager
        // One batch request supplies versions, timestamps, permissions and ApplicationInfo.
        // Previously there was an additional Binder round trip for every installed application.
        packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).mapNotNull { packageInfo ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            try {
                createAppHolder(packageManager, appInfo, packageInfo)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: android.content.res.Resources.NotFoundException) { null }
        }
    }
}

private fun createAppHolder(
    packageManager: PackageManager,
    appInfo: ApplicationInfo,
    packageInfo: android.content.pm.PackageInfo
): AppHolder {
    val appFile = File(appInfo.sourceDir)

    val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()
    val category = getCategoryName(appInfo.category)

    return AppHolder(
        name = appInfo.loadLabel(packageManager).toString(),
        packageName = appInfo.packageName,
        path = appInfo.sourceDir,
        versionName = packageInfo.versionName ?: globalClass.getString(R.string.unknown),
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        },
        size = appFile.length(),
        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) isNot 0,
        installDate = Date(packageInfo.firstInstallTime),
        lastUpdateDate = Date(packageInfo.lastUpdateTime),
        targetSdkVersion = appInfo.targetSdkVersion,
        minSdkVersion =
            appInfo.minSdkVersion,
        permissions = permissions,
        category = category,
        dataDir = appInfo.dataDir,
        uid = appInfo.uid,
        enabled = appInfo.enabled,
        debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) isNot 0
    )
}

private fun getCategoryName(category: Int): String {
    return when (category) {
        ApplicationInfo.CATEGORY_AUDIO -> globalClass.getString(R.string.audio)
        ApplicationInfo.CATEGORY_GAME -> globalClass.getString(R.string.game)
        ApplicationInfo.CATEGORY_IMAGE -> globalClass.getString(R.string.image)
        ApplicationInfo.CATEGORY_MAPS -> globalClass.getString(R.string.maps)
        ApplicationInfo.CATEGORY_NEWS -> globalClass.getString(R.string.news)
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> globalClass.getString(R.string.productivity)
        ApplicationInfo.CATEGORY_SOCIAL -> globalClass.getString(R.string.social)
        ApplicationInfo.CATEGORY_VIDEO -> globalClass.getString(R.string.video)
        else -> emptyString
    }
}