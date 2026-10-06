package com.nixplorer.screen.main.tab.files.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.*
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.ViewCompact
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Tune
import com.nixplorer.screen.preferences.constant.FileItemSizeMap.getFileListFontSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.block
import com.nixplorer.common.detectVerticalSwipe
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.task.CopyTask

@Composable
fun BottomOptionsBar(tab: FilesTab) {
    val state = tab.bottomOptionsBarState.collectAsState().value
    var showMore by remember(tab) { mutableStateOf(false) }
    var showResize by remember(tab) { mutableStateOf(false) }
    fun changeSize(rowDelta: Int = 0, fontDelta: Int = 0) {
        val current = tab.viewConfig
        val font = getFileListFontSize(current.itemSize, current.fontSize)
        val prefs = globalClass.preferencesManager
        prefs.itemSize = (current.itemSize + rowDelta).coerceIn(0, 4)
        prefs.listFontSize = (font + fontDelta).coerceIn(8, 26)
        prefs.useGlobalListSizing = true
    }

    AnimatedVisibility(showMore && tab.selectedFiles.isEmpty(),
        enter = slideInVertically { it } + expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()) {
        Column(Modifier.fillMaxWidth().pointerInput(Unit) {
            detectHorizontalDragGestures { change, _ -> change.consume() }
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(showResize,
                enter = slideInVertically { it } + expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = slideOutVertically { it } + shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()) {
                Row(Modifier.widthIn(max = 448.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BottomOptionsBarButton(Icons.Rounded.ViewCompact, stringResource(R.string.smaller_rows), enabled = tab.viewConfig.itemSize > 0) { changeSize(rowDelta = -1) }
                    BottomOptionsBarButton(Icons.Rounded.ViewAgenda, stringResource(R.string.larger_rows), enabled = tab.viewConfig.itemSize < 4) { changeSize(rowDelta = 1) }
                    BottomOptionsBarButton(Icons.Rounded.TextDecrease, stringResource(R.string.smaller_text), enabled = getFileListFontSize(tab.viewConfig.itemSize, tab.viewConfig.fontSize) > 8) { changeSize(fontDelta = -2) }
                    BottomOptionsBarButton(Icons.Rounded.TextIncrease, stringResource(R.string.larger_text), enabled = getFileListFontSize(tab.viewConfig.itemSize, tab.viewConfig.fontSize) < 26) { changeSize(fontDelta = 2) }
                }
            }
            Row(Modifier.widthIn(max = 448.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BottomOptionsBarButton(Icons.Rounded.Bookmark, stringResource(R.string.bookmarks)) { tab.toggleBookmarksDialog(true) }
                BottomOptionsBarButton(Icons.Rounded.Tune, stringResource(R.string.resize), selected = showResize) { showResize = !showResize }
            }
        }
    }

    AnimatedVisibility(
        visible = state.showQuickOptions && tab.selectedFiles.isNotEmpty(),
        enter = expandIn(expandFrom = Alignment.TopCenter) + slideInVertically(
            initialOffsetY = { it }),
        exit = shrinkOut(shrinkTowards = Alignment.BottomCenter) + slideOutVertically(
            targetOffsetY = { it })

    ) {
        Column {
            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .block(shape = RectangleShape)
                    .detectVerticalSwipe(
                        onSwipeUp = {
                            tab.toggleFileOptionsMenu(tab.selectedFiles[tab.selectedFiles.keys.first()]!!)
                        }
                    )
            ) {
                // Delete
                IconButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        tab.toggleDeleteConfirmationDialog(true)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                // Cut
                IconButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        globalClass.taskManager.addTask(
                            CopyTask(
                                tab.selectedFiles.values.toList(),
                                deleteSourceFiles = true
                            )
                        )
                        tab.unselectAllFiles()
                    }
                ) {
                    Icon(imageVector = Icons.Rounded.ContentCut, contentDescription = null)
                }

                // Copy
                IconButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        globalClass.taskManager.addTask(
                            CopyTask(
                                tab.selectedFiles.values.toList(),
                                deleteSourceFiles = false
                            )
                        )
                        tab.unselectAllFiles()
                    }
                ) {
                    Icon(imageVector = Icons.Rounded.FileCopy, contentDescription = null)
                }

                // Rename
                IconButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        tab.toggleRenameDialog(true)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FormatColorText,
                        contentDescription = null
                    )
                }

                // Properties
                IconButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        tab.toggleFilePropertiesDialog(true)
                    }
                ) {
                    Icon(imageVector = Icons.Rounded.Info, contentDescription = null)
                }
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            // Keep horizontal drags on the action bar away from the tab pager.
            detectHorizontalDragGestures { change, _ -> change.consume() }
        },
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .widthIn(max = 448.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.showEmptyRecycleBinButton) {
                BottomOptionsBarButton(Icons.Rounded.DeleteSweep, stringResource(R.string.empty)) {
                    tab.unselectAllFiles(false)
                    tab.activeFolderContent.forEach {
                        tab.selectedFiles[it.uniquePath] = it
                    }
                    tab.quickReloadFiles()
                    tab.toggleDeleteConfirmationDialog(true)
                }
            } else {
                BottomOptionsBarButton(Icons.Rounded.AddTask, stringResource(R.string.task)) {
                    tab.toggleTasksPanel(true)
                }
            }

            BottomOptionsBarButton(Icons.Rounded.Search, stringResource(R.string.search)) {
                tab.toggleSearchPenal(true)
            }

            if (!state.showEmptyRecycleBinButton && state.showCreateNewContentButton) {
                BottomOptionsBarButton(Icons.Rounded.Add, stringResource(R.string.create)) {
                    tab.toggleCreateNewFileDialog(true)
                }
            }

            if (state.showMoreOptionsButton && tab.selectedFiles.isNotEmpty()) {
                BottomOptionsBarButton(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) {
                    if (tab.selectedFiles.size == tab.activeFolderContent.size) {
                        tab.unselectAllFiles()
                    } else {
                        tab.unselectAllFiles(false)
                        tab.activeFolderContent.forEach {
                            tab.selectedFiles[it.uniquePath] = it
                        }
                        tab.quickReloadFiles()
                    }
                }

                BottomOptionsBarButton(Icons.Rounded.MoreVert, stringResource(R.string.options)) {
                    tab.toggleFileOptionsMenu(tab.selectedFiles[tab.selectedFiles.keys.first()]!!)
                }
            } else {
                BottomOptionsBarButton(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort)) {
                    tab.toggleSortingMenu(true)
                }

                BottomOptionsBarButton(Icons.Rounded.MoreVert, stringResource(R.string.options)) {
                    showMore = !showMore
                    if (!showMore) showResize = false
                }
            }
        }
    }
}

@Composable
fun RowScope.BottomOptionsBarButton(
    imageVector: ImageVector,
    text: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).semantics { contentDescription = text },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(imageVector = imageVector, modifier = Modifier.size(24.dp), contentDescription = null)
            if (globalClass.preferencesManager.showBottomBarLabels) {
                Text(
                    text = text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
