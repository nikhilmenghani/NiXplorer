package com.nixplorer.screen.main.tab.files.ui.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Task
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevinnzou.compose.swipebox.SwipeBox
import com.kevinnzou.compose.swipebox.SwipeDirection
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.ui.BottomSheetDialog
import com.nixplorer.common.ui.FileOperationTheme
import com.nixplorer.common.ui.Space
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.conversion.ConversionTask
import com.nixplorer.screen.main.tab.files.conversion.ConversionTaskParameters
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.holder.VirtualFileHolder
import com.nixplorer.screen.main.tab.files.service.ContentOperationService.Companion.removeBackgroundTask
import com.nixplorer.screen.main.tab.files.task.CompressTask
import com.nixplorer.screen.main.tab.files.task.CopyTask
import com.nixplorer.screen.main.tab.files.task.CopyTaskParameters
import com.nixplorer.screen.main.tab.files.task.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun TaskPanel(
    show: Boolean,
    tab: FilesTab,
    onDismissRequest: () -> Unit
) {
    if (show) {
        val context = LocalContext.current

        val runningTasks = remember { mutableStateListOf<Task>() }
        val pausedTasks = remember { mutableStateListOf<Task>() }
        val failedTasks = remember { mutableStateListOf<Task>() }
        val pendingTasks = remember { mutableStateListOf<Task>() }
        val invalidTasks = remember { mutableStateListOf<Task>() }
        val completedTasks = remember { mutableStateListOf<Task>() }
        var detailsTask by remember { mutableStateOf<Task?>(null) }

        LaunchedEffect(Unit) {
            globalClass.taskManager.validateTasks()

            while (tab.dialogsState.value.showTasksPanel) {
                refreshTaskList(runningTasks, globalClass.taskManager.runningTasks)
                refreshTaskList(pausedTasks, globalClass.taskManager.pausedTasks)
                refreshTaskList(failedTasks, globalClass.taskManager.failedTasks)
                refreshTaskList(pendingTasks, globalClass.taskManager.pendingTasks)
                refreshTaskList(invalidTasks, globalClass.taskManager.invalidTasks)
                val latestCompleted = synchronized(globalClass.taskManager.completedTasks) {
                    globalClass.taskManager.completedTasks.toList().takeLast(20).asReversed()
                }
                if (completedTasks != latestCompleted) {
                    completedTasks.clear()
                    completedTasks.addAll(latestCompleted)
                }
                delay(250)
            }
        }

        FileOperationTheme {
            BottomSheetDialog(
                onDismissRequest = onDismissRequest
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * .86f).dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.TaskAlt, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.tasks), style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold)
                                val taskCount = runningTasks.size + pausedTasks.size + failedTasks.size + pendingTasks.size + invalidTasks.size + completedTasks.size
                                Text(pluralStringResource(R.plurals.task_ui_count, taskCount, taskCount),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onDismissRequest, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        AnimatedVisibility(
                            visible = !runningTasks.isEmpty() || !pausedTasks.isEmpty() ||
                                    !failedTasks.isEmpty() || !pendingTasks.isEmpty() || !invalidTasks.isEmpty() || !completedTasks.isEmpty(),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(animationSpec = tween(300)),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Running Tasks
                                if (runningTasks.isNotEmpty()) {
                                    item {
                                        TaskCategoryHeader(
                                            title = stringResource(R.string.tasks_running),
                                            count = runningTasks.size,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    items(
                                        runningTasks.sortedBy { it.metadata.creationTime }.reversed(),
                                        key = { "running-${it.id}" }) { task ->
                                        RunningTaskItem(
                                            task = task,
                                            onClick = {
                                                tab.toggleTasksPanel(false)
                                                globalClass.taskManager.bringToForeground(task)
                                            }
                                        )
                                    }
                                    item { Space(8.dp) }
                                }

                                // Paused Tasks
                                if (pausedTasks.isNotEmpty()) {
                                    item {
                                        TaskCategoryHeader(
                                            title = stringResource(R.string.tasks_paused),
                                            count = pausedTasks.size,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(
                                        pausedTasks.sortedBy { it.metadata.creationTime }.reversed(),
                                        key = { "paused-${it.id}" }) { task ->
                                        SwipeableTaskItem(
                                            task = task,
                                            icon = Icons.Default.Pause,
                                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            onSwipeToDelete = {
                                                pausedTasks.remove(task)
                                                globalClass.taskManager.removeTask(task.id)
                                                removeBackgroundTask(context, task.id)
                                            },
                                            onClick = {
                                                globalClass.taskManager.continueTask(task.id)
                                                tab.toggleTasksPanel(false)
                                            }
                                        )
                                    }
                                    item { Space(8.dp) }
                                }

                                // Failed Tasks
                                if (failedTasks.isNotEmpty()) {
                                    item {
                                        TaskCategoryHeader(
                                            title = stringResource(R.string.tasks_failed),
                                            count = failedTasks.size,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    items(
                                        failedTasks.sortedBy { it.metadata.creationTime }.reversed(),
                                        key = { "failed-${it.id}" }) { task ->
                                        SwipeableTaskItem(
                                            task = task,
                                            icon = Icons.Default.ErrorOutline,
                                            iconTint = MaterialTheme.colorScheme.error,
                                            onSwipeToDelete = {
                                                failedTasks.remove(task)
                                                globalClass.taskManager.removeTask(task.id)
                                                removeBackgroundTask(context, task.id)
                                            },
                                            onClick = {
                                                globalClass.taskManager.continueTask(task.id)
                                                tab.toggleTasksPanel(false)
                                            }
                                        )
                                    }
                                    item { Space(8.dp) }
                                }

                                // Pending Tasks
                                if (pendingTasks.isNotEmpty()) {
                                    item {
                                        TaskCategoryHeader(
                                            title = stringResource(R.string.tasks_pending),
                                            count = pendingTasks.size,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(
                                        pendingTasks.sortedBy { it.metadata.creationTime }.reversed(),
                                        key = { "pending-${it.id}" }) { task ->
                                        val conversion = task as? ConversionTask
                                        val sourceNames = conversion?.sourceContent?.take(3)?.joinToString("\n") { it.displayName }.orEmpty()
                                        val preview = if (conversion != null) {
                                            val names = if (conversion.sourceContent.size > 3) {
                                                stringResource(R.string.conversion_pending_more, sourceNames, conversion.sourceContent.size - 3)
                                            } else sourceNames
                                            stringResource(R.string.conversion_pending_sources, names)
                                        } else null
                                        val localDestination = (tab.activeFolder as? LocalFileHolder)?.file
                                        val canRunHere = localDestination != null && localDestination.isDirectory && localDestination.canWrite()
                                        val actionHint = if (conversion != null) {
                                            if (canRunHere) {
                                                stringResource(R.string.conversion_run_here, localDestination!!.absolutePath)
                                            } else stringResource(R.string.conversion_pending_destination)
                                        } else null
                                        SwipeableTaskItem(
                                            task = task,
                                            icon = Icons.Default.HourglassEmpty,
                                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            preview = preview,
                                            actionHint = actionHint,
                                            actionEnabled = canRunHere,
                                            onSwipeToDelete = {
                                                pendingTasks.remove(task)
                                                globalClass.taskManager.removeTask(task.id)
                                            },
                                            onClick = {
                                                if (task is ConversionTask) {
                                                    val currentDestination = (tab.activeFolder as? LocalFileHolder)?.file
                                                    if (currentDestination == null || !currentDestination.isDirectory || !currentDestination.canWrite()) {
                                                        globalClass.showMsg(globalClass.getString(R.string.conversion_pending_destination))
                                                        return@SwipeableTaskItem
                                                    }
                                                    globalClass.taskManager.runTask(task.id, ConversionTaskParameters(currentDestination.absolutePath))
                                                    tab.toggleTasksPanel(false)
                                                    return@SwipeableTaskItem
                                                }
                                                if (tab.activeFolder is VirtualFileHolder || tab !is FilesTab) {
                                                    globalClass.showMsg(globalClass.getString(R.string.can_not_run_tasks))
                                                    return@SwipeableTaskItem
                                                }
                                                tab.toggleTasksPanel(false)
                                                when (task) {
                                                    is CopyTask -> {
                                                        globalClass.taskManager.runTask(
                                                            task.id,
                                                            CopyTaskParameters(tab.activeFolder)
                                                        )
                                                    }

                                                    is CompressTask -> tab.toggleCompressTaskDialog(task)
                                                }
                                            }
                                        )
                                    }
                                    item { Space(8.dp) }
                                }

                                // Invalid Tasks
                                if (invalidTasks.isNotEmpty()) {
                                    item {
                                        TaskCategoryHeader(
                                            title = stringResource(R.string.tasks_invalid),
                                            count = invalidTasks.size,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(
                                        invalidTasks.sortedBy { it.metadata.creationTime }.reversed(),
                                        key = { "invalid-${it.id}" }) { task ->
                                        SwipeableTaskItem(
                                            task = task,
                                            icon = Icons.Default.Warning,
                                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            onSwipeToDelete = {
                                                invalidTasks.remove(task)
                                                globalClass.taskManager.removeTask(task.id)
                                            },
                                            onClick = { }
                                        )
                                    }
                                }

                                if (completedTasks.isNotEmpty()) {
                                    item {
                                        TaskCategoryHeader(
                                            title = stringResource(R.string.completed),
                                            count = completedTasks.size,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    item {
                                        Text(stringResource(R.string.task_ui_completed_hint),
                                            modifier = Modifier.padding(horizontal = 8.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    items(completedTasks, key = { "completed-${it.id}" }) { task ->
                                        SwipeableTaskItem(
                                            task = task,
                                            icon = Icons.Rounded.TaskAlt,
                                            iconTint = MaterialTheme.colorScheme.primary,
                                            onSwipeToDelete = {
                                                completedTasks.remove(task)
                                                globalClass.taskManager.removeTask(task.id)
                                            },
                                            onClick = { detailsTask = task }
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = runningTasks.isEmpty() && pausedTasks.isEmpty() &&
                                    failedTasks.isEmpty() && pendingTasks.isEmpty() && invalidTasks.isEmpty() && completedTasks.isEmpty()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp, horizontal = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Task,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Space(16.dp)
                                Text(
                                    text = stringResource(R.string.empty),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Space(8.dp)
                                Text(
                                    text = stringResource(R.string.no_tasks_available),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            detailsTask?.let { task ->
                AlertDialog(
                    onDismissRequest = { detailsTask = null },
                    shape = RoundedCornerShape(28.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    icon = { Icon(Icons.Rounded.TaskAlt, null, tint = MaterialTheme.colorScheme.primary) },
                    title = { Text(task.metadata.title, style = MaterialTheme.typography.titleLarge) },
                    text = {
                        SelectionContainer {
                            Column(
                                Modifier.fillMaxWidth()
                                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (task.metadata.subtitle.isNotBlank()) Text(task.metadata.subtitle,
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Text(task.progressMonitor.summary.ifBlank { task.metadata.displayDetails },
                                        modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { detailsTask = null }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                            Text(stringResource(R.string.close))
                        }
                    }
                )
            }
        }
    }
}

/** Task state changes on a service thread; copy its collection while holding its monitor. */
private fun refreshTaskList(target: MutableList<Task>, source: MutableCollection<Task>) {
    val snapshot = synchronized(source) { source.toList() }
    if (target != snapshot) {
        target.clear()
        target.addAll(snapshot)
    }
}

@Composable
private fun TaskCategoryHeader(
    title: String,
    count: Int,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = color, modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun RunningTaskItem(task: Task, onClick: () -> Unit) {
    var progress by remember(task.id) { mutableFloatStateOf(task.progressMonitor.progress) }
    var processName by remember(task.id) { mutableStateOf(task.progressMonitor.processName) }
    var contentName by remember(task.id) { mutableStateOf(task.progressMonitor.contentName) }
    var totalContent by remember(task.id) { mutableIntStateOf(task.progressMonitor.totalContent) }
    var remainingContent by remember(task.id) { mutableIntStateOf(task.progressMonitor.remainingContent) }

    LaunchedEffect(task.id) {
        while (true) {
            progress = task.progressMonitor.progress
            processName = task.progressMonitor.processName
            contentName = task.progressMonitor.contentName
            totalContent = task.progressMonitor.totalContent
            remainingContent = task.progressMonitor.remainingContent
            delay(100)
        }
    }
    val fraction = progress.coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text(task.metadata.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
                if (fraction > 0f) {
                    Text(stringResource(R.string.task_ui_progress_percent, (fraction * 100).toInt()),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (processName.isNotBlank()) {
                Text(processName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (contentName.isNotBlank()) {
                Text(contentName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val indicatorModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            if (fraction > 0f) {
                LinearProgressIndicator(progress = { fraction }, modifier = indicatorModifier,
                    color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            } else {
                LinearProgressIndicator(modifier = indicatorModifier, color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            }
            if (totalContent > 0) {
                Text(stringResource(R.string.task_ui_progress_count,
                    (totalContent - remainingContent).coerceIn(0, totalContent), totalContent),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableTaskItem(
    task: Task,
    icon: ImageVector,
    iconTint: Color,
    onSwipeToDelete: () -> Unit,
    onClick: () -> Unit,
    preview: String? = null,
    actionHint: String? = null,
    actionEnabled: Boolean = true
) {
    val coroutineScope = rememberCoroutineScope()
    SwipeBox(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .animateContentSize(),
        swipeDirection = SwipeDirection.EndToStart,
        endContentWidth = 72.dp,
        endContent = { swipeableState, _ ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(72.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                    )
                    .clickable {
                        onSwipeToDelete()
                        coroutineScope.launch { swipeableState.animateTo(0) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { _, _, _ ->
        TaskItem(
            task = task,
            icon = icon,
            iconTint = iconTint,
            onClick = onClick,
            preview = preview,
            actionHint = actionHint,
            actionEnabled = actionEnabled
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskItem(
    task: Task,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    preview: String? = null,
    actionHint: String? = null,
    actionEnabled: Boolean = true
) {
    var expanded by remember(task.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    expanded = !expanded
                }
            )
            .animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(task.metadata.title, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                    if (task.metadata.subtitle.isNotBlank()) {
                        Text(task.metadata.subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(task.metadata.creationTime, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!preview.isNullOrBlank()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(preview, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!actionHint.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(actionHint, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalIconButton(onClick = onClick, enabled = actionEnabled, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.PlayArrow, stringResource(R.string.task_ui_run_here))
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(task.progressMonitor.summary.ifEmpty { task.metadata.displayDetails },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
