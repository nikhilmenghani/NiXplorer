package com.nixplorer.screen.main

import androidx.compose.foundation.lazy.LazyListState
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.emptyString
import com.nixplorer.screen.main.tab.Tab
import com.nixplorer.screen.main.tab.files.holder.StorageDevice

data class MainActivityState(
    val title: String = globalClass.getString(R.string.main_activity_title),
    val subtitle: String = emptyString,
    val showAppInfoDialog: Boolean = false,
    val showJumpToPathDialog: Boolean = false,
    val showSaveEditorFilesDialog: Boolean = false,
    val showStartupTabsDialog: Boolean = false,
    val isSavingFiles: Boolean = false,
    val selectedTabIndex: Int = 0,
    val storageDevices: List<StorageDevice> = emptyList(),
    val tabs: List<Tab> = emptyList(),
    val tabLayoutState: LazyListState = LazyListState(),
    val hasNewUpdate: Boolean = false
)