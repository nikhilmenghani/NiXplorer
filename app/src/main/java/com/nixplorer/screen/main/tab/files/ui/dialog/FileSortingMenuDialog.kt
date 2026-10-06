package com.nixplorer.screen.main.tab.files.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.ui.BottomSheetDialog
import com.nixplorer.common.ui.Space
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.misc.FileSortingPrefs
import com.nixplorer.screen.main.tab.files.misc.SortingMethod

@Composable
fun FileSortingMenuDialog(
    show: Boolean,
    tab: FilesTab,
    onDismissRequest: () -> Unit,
) {
    if (show) {
        val prefs = globalClass.preferencesManager
        val specificOptions = prefs.getSortingPrefsFor(tab.activeFolder)

        var applyForThisFileOnly by remember(tab.activeFolder.uniquePath) {
            mutableStateOf(specificOptions.applyForThisFileOnly)
        }
        var sortingMethod by remember(tab.activeFolder.uniquePath) {
            mutableIntStateOf(specificOptions.sortMethod)
        }
        var showFoldersFirst by remember(tab.activeFolder.uniquePath) {
            mutableStateOf(specificOptions.showFoldersFirst)
        }
        var directionMask by remember(tab.activeFolder.uniquePath) {
            mutableIntStateOf(specificOptions.directionMask.let {
                val bit = 1 shl specificOptions.sortMethod
                if (specificOptions.reversedFor(specificOptions.sortMethod)) it or bit else it and bit.inv()
            })
        }
        val reverseOrder = directionMask and (1 shl sortingMethod) != 0
        fun reversed(method: Int) = directionMask and (1 shl method) != 0
        fun toggleDirection(method: Int) {
            directionMask = directionMask xor (1 shl method)
            sortingMethod = method
        }

        fun selectMethod(method: Int) {
            sortingMethod = method
        }

        fun updateForThisFolder() {
            prefs.setSortingPrefsFor(
                content = tab.activeFolder,
                prefs = FileSortingPrefs(
                    sortMethod = sortingMethod,
                    showFoldersFirst = showFoldersFirst,
                    reverseSorting = reverseOrder,
                    directionMask = directionMask,
                    configuredDirections = 30,
                    applyForThisFileOnly = true
                )
            )
        }

        BottomSheetDialog(
            skipPartiallyExpanded = true,
            onDismissRequest = {
                if (applyForThisFileOnly) {
                    if (showFoldersFirst != prefs.showFoldersFirst
                        || reverseOrder != prefs.reverse
                        || sortingMethod != prefs.defaultSortMethod
                        || directionMask != prefs.sortDirectionMask
                    ) {
                        updateForThisFolder()
                    } else {
                        prefs.deleteSortingPrefsFor(tab.activeFolder)
                    }
                } else {
                    prefs.showFoldersFirst = showFoldersFirst
                    prefs.reverse = reverseOrder
                    prefs.defaultSortMethod = sortingMethod
                    prefs.sortDirectionMask = directionMask
                    prefs.configuredSortDirections = 30
                }
                onDismissRequest()
            }
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SortByAlpha,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Space(8.dp)
                    Text(
                        text = stringResource(R.string.sort_by),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Scope Selection Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    SwitchSettingItem(
                        icon = Icons.Rounded.ArrowDownward,
                        title = stringResource(R.string.apply_to_this_folder_only),
                        checked = applyForThisFileOnly,
                        onCheckedChange = { applyForThisFileOnly = it }
                    )
                }

                Space(size = 12.dp)

                // Sort Method Section
                Text(
                    text = "Sort Method",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .selectableGroup()
                            .padding(8.dp)
                    ) {
                        RadioButtonItem(
                            icon = Icons.Rounded.SortByAlpha,
                            text = stringResource(if (reversed(SortingMethod.SORT_BY_NAME)) R.string.name_z_a else R.string.name_a_z),
                            reversed = reversed(SortingMethod.SORT_BY_NAME), onToggleDirection = { toggleDirection(SortingMethod.SORT_BY_NAME) },
                            selected = sortingMethod == SortingMethod.SORT_BY_NAME,
                            onClick = { selectMethod(SortingMethod.SORT_BY_NAME) }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )

                        RadioButtonItem(
                            icon = Icons.Rounded.DateRange,
                            text = stringResource(if (reversed(SortingMethod.SORT_BY_DATE)) R.string.date_older else R.string.date_newer),
                            reversed = reversed(SortingMethod.SORT_BY_DATE), onToggleDirection = { toggleDirection(SortingMethod.SORT_BY_DATE) },
                            selected = sortingMethod == SortingMethod.SORT_BY_DATE,
                            onClick = { selectMethod(SortingMethod.SORT_BY_DATE) }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )

                        RadioButtonItem(
                            icon = Icons.AutoMirrored.Rounded.Sort,
                            text = stringResource(if (reversed(SortingMethod.SORT_BY_SIZE)) R.string.size_larger else R.string.size_smaller),
                            reversed = reversed(SortingMethod.SORT_BY_SIZE), onToggleDirection = { toggleDirection(SortingMethod.SORT_BY_SIZE) },
                            selected = sortingMethod == SortingMethod.SORT_BY_SIZE,
                            onClick = { selectMethod(SortingMethod.SORT_BY_SIZE) }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )

                        RadioButtonItem(
                            icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
                            text = stringResource(R.string.type) + " · " + stringResource(if (reversed(SortingMethod.SORT_BY_TYPE)) R.string.order_z_a else R.string.order_a_z),
                            reversed = reversed(SortingMethod.SORT_BY_TYPE), onToggleDirection = { toggleDirection(SortingMethod.SORT_BY_TYPE) },
                            selected = sortingMethod == SortingMethod.SORT_BY_TYPE,
                            onClick = { selectMethod(SortingMethod.SORT_BY_TYPE) }
                        )
                    }
                }

                Space(size = 12.dp)

                // Display Options Section
                Text(
                    text = "Display Options",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        SwitchSettingItem(
                            icon = Icons.Rounded.Folder,
                            title = stringResource(R.string.folders_first),
                            checked = showFoldersFirst,
                            onCheckedChange = { showFoldersFirst = it }
                        )


                    }
                }
            }
        }
    }
}

@Composable
private fun RadioButtonItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    selected: Boolean,
    reversed: Boolean,
    onToggleDirection: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onToggleDirection) {
                Icon(if (reversed) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(R.string.toggle_sort_direction, text))
            }
            RadioButton(
                selected = selected,
                onClick = null
            )
        }
    }
}

@Composable
private fun SwitchSettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = { onCheckedChange(!checked) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
