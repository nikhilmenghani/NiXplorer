package com.nixplorer.screen.main.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SdStorage
import com.nixplorer.screen.main.tab.files.misc.StorageDeviceType.INTERNAL_STORAGE
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nixplorer.screen.main.tab.files.holder.StorageDevice
import com.nixplorer.screen.main.tab.files.provider.StorageProvider
import androidx.compose.material3.LinearProgressIndicator
import com.nixplorer.common.toFormattedSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.EditAttributes
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewComfy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.icons.NiXplorerIcons
import com.nixplorer.common.icons.Upgrade
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.home.HomeTab
import com.nixplorer.screen.preferences.PreferencesActivity

@Composable
fun Toolbar(
    title: String,
    subtitle: String,
    onToggleAppInfoDialog: (Boolean) -> Unit,
    hasNewUpdate: Boolean
) {
    var showStorage by remember { mutableStateOf(false) }
    var loadingStorage by remember { mutableStateOf(false) }
    val storage = remember { mutableStateListOf<StorageDevice>() }
    val context = LocalContext.current
    LaunchedEffect(showStorage) {
        if (showStorage) {
            loadingStorage = true
            try {
                val devices = withContext(Dispatchers.IO) { StorageProvider.getStorageDevices(context) }
                storage.clear()
                storage.addAll(devices)
            } finally { loadingStorage = false }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            IconButton(
                onClick = { showStorage = true }
            ) {
                Icon(imageVector = Icons.Rounded.Menu, contentDescription = stringResource(R.string.storage))
            }
            DropdownMenu(expanded = showStorage, onDismissRequest = { showStorage = false },
                modifier = Modifier.widthIn(min = 240.dp, max = 288.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(stringResource(R.string.storage), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loadingStorage) LinearProgressIndicator(Modifier.fillMaxWidth())
                storage.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(device.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.analysis_free_of_total,
                                    (device.totalSize - device.usedSize).coerceAtLeast(0).toFormattedSize(), device.totalSize.toFormattedSize()),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val fraction = if (device.totalSize > 0) (device.usedSize.toFloat() / device.totalSize).coerceIn(0f, 1f) else 0f
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp),
                                    color = if (fraction > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        },
                        leadingIcon = { Icon(if (device.type == INTERNAL_STORAGE) Icons.Rounded.FolderOpen else Icons.Rounded.SdStorage, null, Modifier.size(20.dp)) },
                        onClick = {
                            showStorage = false
                            globalClass.mainActivityManager.replaceCurrentTabWith(FilesTab(device.contentHolder))
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.analyze_storage), style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        showStorage = false
                        context.startActivity(Intent(context, com.nixplorer.screen.storage.StorageAnalyzerActivity::class.java))
                    }, leadingIcon = { Icon(Icons.Rounded.ViewComfy, null, Modifier.size(20.dp)) })
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                maxLines = 1,
                lineHeight = 20.sp,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedVisibility(visible = subtitle.isNotEmpty()) {
                Text(
                    modifier = Modifier.alpha(0.7f),
                    text = subtitle,
                    fontSize = 10.sp,
                    maxLines = 1,
                    lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        MoreOptionsButton()
    }
}

@Composable
fun MoreOptionsButton() {
    val mainActivityManager = globalClass.mainActivityManager
    val context = LocalContext.current

    var showOptionsMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { showOptionsMenu = true }
        ) {
            Icon(imageVector = Icons.Rounded.MoreVert, contentDescription = null)
        }

        DropdownMenu(
            expanded = showOptionsMenu,
            onDismissRequest = { showOptionsMenu = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val state by mainActivityManager.state.collectAsState()

            if (state.hasNewUpdate) {
                DropdownMenuItem(
                    text = {
                        Text(text = stringResource(R.string.new_update_available))
                    },
                    onClick = {
                        mainActivityManager.toggleAppInfoDialog(true)
                        showOptionsMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = NiXplorerIcons.Upgrade,
                            contentDescription = null
                        )
                    }
                )
            }

            if (mainActivityManager.getActiveTab() is FilesTab) {
                val showHiddenFiles = globalClass.preferencesManager.showHiddenFiles
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.show_hidden_files)
                        )
                    },
                    onClick = {
                        globalClass.preferencesManager.showHiddenFiles =
                            !showHiddenFiles
                        (mainActivityManager.getActiveTab() as FilesTab).onTabResumed()
                        showOptionsMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.RemoveRedEye,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = if (showHiddenFiles)
                                Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = null
                        )
                    }
                )

                HorizontalDivider()

                DropdownMenuItem(
                    text = {
                        Text(text = stringResource(R.string.view_type))
                    },
                    onClick = {
                        (mainActivityManager.getActiveTab() as FilesTab).toggleViewConfigDialog(
                            true
                        )
                        showOptionsMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.ViewComfy,
                            contentDescription = null
                        )
                    }
                )
            }

            if (mainActivityManager.getActiveTab() is HomeTab) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.customize_home_tab)) },
                    onClick = {
                        (mainActivityManager.getActiveTab() as HomeTab).showCustomizeHomeTabDialog =
                            true
                        showOptionsMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.EditAttributes,
                            contentDescription = null
                        )
                    }
                )
            }

            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.manage_startup_tabs)) },
                onClick = {
                    mainActivityManager.toggleStartupTabsDialog(true)
                    showOptionsMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesomeMotion,
                        contentDescription = null
                    )
                }
            )

            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.preferences)) },
                onClick = {
                    context.startActivity(Intent(context, PreferencesActivity::class.java))
                    showOptionsMenu = false
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Settings, contentDescription = null)
                }
            )

            run {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.about)) },
                    onClick = {
                        mainActivityManager.toggleAppInfoDialog(true)
                        showOptionsMenu = false
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.Info, contentDescription = null)
                    }
                )
            }
        }
    }
}
