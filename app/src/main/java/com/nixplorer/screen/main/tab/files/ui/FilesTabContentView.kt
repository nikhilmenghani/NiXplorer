package com.nixplorer.screen.main.tab.files.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.screen.main.tab.home.HomeTab
import kotlin.math.abs
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.ui.dialog.ApkPreviewDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.BookmarksDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.CreateNewFileDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.DeleteConfirmationDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.FileCompressionDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.FileOptionsMenuDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.FilePropertiesDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.FileSortingMenuDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.FileViewConfigDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.ImportPrefsDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.OpenWithAppListDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.RenameDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.SearchDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.TaskConflictDialog
import com.nixplorer.screen.main.tab.files.ui.dialog.TaskPanel
import com.nixplorer.screen.main.tab.files.ui.dialog.TaskRunningDialog

@Composable
fun ColumnScope.FilesTabContentView(tab: FilesTab) {
    Dialogs(tab)
    BreadcrumbBar(tab)
    InfoRow()
    HorizontalDivider(modifier = Modifier, thickness = 1.dp)
    val swipeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    Column(
        Modifier.weight(1f).fillMaxWidth().then(
            if (tab.showCategories) Modifier.pointerInput(tab.id, swipeThreshold) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = {
                        if (abs(distance) >= swipeThreshold) {
                            val direction = if (distance < 0) 1 else -1
                            val current = tab.selectedCategory?.let { tab.categories.indexOf(it) + 1 } ?: 0
                            val next = current + direction
                            if (next in 0..tab.categories.size) {
                                tab.selectedCategory = if (next == 0) null else tab.categories[next - 1]
                                tab.reloadFiles()
                            } else {
                                val manager = globalClass.mainActivityManager
                                val mainState = manager.state.value
                                val mainIndex = mainState.tabs.indexOf(tab) + direction
                                if (mainIndex in mainState.tabs.indices) {
                                    manager.selectTabAt(mainIndex)
                                } else if (direction > 0) {
                                    manager.addTabAndSelect(HomeTab())
                                }
                            }
                        }
                        distance = 0f
                    }
                ) { change, delta ->
                    change.consume()
                    distance += delta
                }
            } else Modifier
        )
    ) {
        FilesList(tab)
    }
    BottomOptionsBar(tab)
}


@Composable
fun Dialogs(tab: FilesTab) {
    val dialogsState = tab.dialogsState.collectAsState()

    ApkPreviewDialog(
        show = dialogsState.value.showApkDialog && tab.targetFile != null && tab.targetFile is LocalFileHolder,
        tab = tab,
        onDismissRequest = { tab.toggleApkDialog(null) }
    )

    OpenWithAppListDialog(
        show = dialogsState.value.showOpenWithDialog && tab.targetFile != null && tab.targetFile!! is LocalFileHolder,
        tab = tab,
        onDismissRequest = { tab.toggleOpenWithDialog(false) }
    )

    BookmarksDialog(
        show = dialogsState.value.showBookmarkDialog,
        tab = tab,
        onDismissRequest = { tab.toggleBookmarksDialog(false) }
    )

    SearchDialog(
        show = dialogsState.value.showSearchPenal,
        tab = tab,
        onDismissRequest = { tab.toggleSearchPenal(false) }
    )

    FileSortingMenuDialog(
        show = dialogsState.value.showSortingMenu,
        tab = tab,
        onDismissRequest = {
            tab.toggleSortingMenu(false)
            tab.reloadFiles()
        }
    )

    FileViewConfigDialog(
        show = dialogsState.value.showViewConfigDialog,
        tab = tab,
        onDismissRequest = {
            tab.toggleViewConfigDialog(false)
            tab.updateDisplayConfig()
        }
    )

    DeleteConfirmationDialog(
        show = dialogsState.value.showConfirmDeleteDialog,
        tab = tab,
        onDismissRequest = { tab.toggleDeleteConfirmationDialog(false) }
    )

    CreateNewFileDialog(
        show = dialogsState.value.showCreateNewFileDialog,
        tab = tab,
        onDismissRequest = { tab.toggleCreateNewFileDialog(false) }
    )

    RenameDialog(
        show = dialogsState.value.showRenameDialog && tab.selectedFiles.isNotEmpty(),
        tab = tab,
        onDismissRequest = { tab.toggleRenameDialog(false) }
    )

    FileCompressionDialog(
        show = dialogsState.value.showNewZipFileDialog && tab.compressTaskHolder != null,
        tab = tab,
        onDismissRequest = { tab.toggleCompressTaskDialog(null) }
    )

    FileOptionsMenuDialog(
        show = dialogsState.value.showFileOptionsDialog && tab.targetFile != null,
        tab = tab,
        onDismissRequest = { tab.toggleFileOptionsMenu(null, false) }
    )

    FilePropertiesDialog(
        show = dialogsState.value.showFileProperties,
        tab = tab,
        onDismissRequest = { tab.toggleFilePropertiesDialog(false) }
    )

    TaskPanel(
        show = dialogsState.value.showTasksPanel,
        tab = tab,
        onDismissRequest = { tab.toggleTasksPanel(false) }
    )

    ImportPrefsDialog(
        show = dialogsState.value.showImportPrefsDialog,
        tab = tab,
        onDismissRequest = { tab.toggleImportPrefsDialog(null) }
    )

    TaskRunningDialog()

    TaskConflictDialog()
}