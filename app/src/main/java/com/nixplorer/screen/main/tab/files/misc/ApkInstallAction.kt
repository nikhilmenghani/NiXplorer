package com.nixplorer.screen.main.tab.files.misc

internal enum class ApkInstallAction { INSTALL, REINSTALL, UPDATE, DOWNGRADE }

internal fun apkInstallAction(apkVersionCode: Long, installedVersionCode: Long?): ApkInstallAction = when {
    installedVersionCode == null -> ApkInstallAction.INSTALL
    apkVersionCode == installedVersionCode -> ApkInstallAction.REINSTALL
    apkVersionCode > installedVersionCode -> ApkInstallAction.UPDATE
    else -> ApkInstallAction.DOWNGRADE
}
