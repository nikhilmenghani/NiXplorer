package com.nixplorer.common

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R

fun openExternally(context: Context, uri: Uri, mimeType: String): Boolean {
    return try {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newRawUri("File", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val ownActivities = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
            .activities.orEmpty().map { ComponentName(context.packageName, it.name) }.toTypedArray()
        val chooser = Intent.createChooser(view, context.getString(R.string.open_externally)).apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownActivities)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        true
    } catch (e: Exception) {
        globalClass.logger.logError(e)
        globalClass.showMsg(R.string.no_app_can_open_file)
        false
    }
}
