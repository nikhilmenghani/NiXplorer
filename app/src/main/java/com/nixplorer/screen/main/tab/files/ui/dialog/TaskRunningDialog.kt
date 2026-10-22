package com.nixplorer.screen.main.tab.files.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.ui.FileOperationTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TaskRunningDialog() {
    val dialogInfo = globalClass.taskManager.runningTaskDialogInfo
    val progress = dialogInfo.progressMonitor
    val task = dialogInfo.linkedTask
    if (!dialogInfo.showDialog || progress == null || task == null) return

    var isAborted by remember(task.id) { mutableStateOf(false) }
    val fraction = progress.progress.coerceIn(0f, 1f)
    val processed = (progress.totalContent - progress.remainingContent).coerceIn(0, progress.totalContent.coerceAtLeast(0))

    FileOperationTheme {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * .84f).dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    LoadingIndicator(modifier = Modifier.size(28.dp),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                            Text(
                                text = progress.taskTitle.ifBlank { stringResource(R.string.preparing) },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        if (isAborted) stringResource(R.string.stopping)
                                        else progress.processName.ifBlank { stringResource(R.string.preparing) },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (progress.contentName.isNotBlank()) {
                                        Text(progress.contentName, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                    val indicatorModifier = Modifier.fillMaxWidth()
                                    if (fraction > 0f) {
                                        LinearWavyProgressIndicator(progress = { fraction }, modifier = indicatorModifier,
                                            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                                    } else {
                                        LinearWavyProgressIndicator(modifier = indicatorModifier, color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            if (progress.totalContent > 0) stringResource(R.string.task_ui_progress_count, processed, progress.totalContent)
                                            else stringResource(R.string.preparing),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (fraction > 0f) {
                                            Text(stringResource(R.string.task_ui_progress_percent, (fraction * 100).toInt()),
                                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                            if (task.metadata.canMoveToBackground) {
                                Text(stringResource(R.string.task_ui_running_background), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (task.metadata.canMoveToBackground) {
                                TextButton(
                                    onClick = { globalClass.taskManager.hideRunningTaskDialog() },
                                    enabled = !isAborted,
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text(stringResource(R.string.hide))
                                }
                            }
                            if (task.metadata.isCancellable) {
                                FilledTonalButton(
                                    onClick = { task.abortTask(); isAborted = true },
                                    enabled = !isAborted,
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    shapes = ButtonDefaults.shapes(),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                                    Text(if (isAborted) stringResource(R.string.stopping) else stringResource(R.string.stop),
                                        modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
