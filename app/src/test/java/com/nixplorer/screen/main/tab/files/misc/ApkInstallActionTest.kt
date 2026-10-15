package com.nixplorer.screen.main.tab.files.misc

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkInstallActionTest {
    @Test fun selectsCorrectActionForEveryVersionRelationship() {
        assertEquals(ApkInstallAction.INSTALL, apkInstallAction(10L, null))
        assertEquals(ApkInstallAction.REINSTALL, apkInstallAction(10L, 10L))
        assertEquals(ApkInstallAction.UPDATE, apkInstallAction(11L, 10L))
        assertEquals(ApkInstallAction.DOWNGRADE, apkInstallAction(9L, 10L))
    }

    @Test fun supportsLongVersionCodesWithoutIntegerOverflow() {
        assertEquals(ApkInstallAction.UPDATE, apkInstallAction(5_000_000_001L, 5_000_000_000L))
        assertEquals(ApkInstallAction.REINSTALL, apkInstallAction(5_000_000_001L, 5_000_000_001L))
    }
}
