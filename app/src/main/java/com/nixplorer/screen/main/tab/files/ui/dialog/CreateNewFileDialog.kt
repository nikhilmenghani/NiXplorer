package com.nixplorer.screen.main.tab.files.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.isValidAsFileName
import com.nixplorer.common.ui.CheckableText
import com.nixplorer.common.ui.SheetEntryField
import com.nixplorer.common.ui.TextEntryTopSheet
import com.nixplorer.screen.main.tab.files.FilesTab
import kotlinx.coroutines.launch

@Composable
fun CreateNewFileDialog(show: Boolean, tab: FilesTab, onDismissRequest: () -> Unit) {
    if (!show) return
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(true) }
    var openFolder by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val names = tab.activeFolderContent.map { it.displayName }
    val error = when {
        name.isBlank() -> ""
        !name.isValidAsFileName() -> stringResource(R.string.invalid_file_name)
        name in names -> stringResource(R.string.similar_file_exists)
        else -> ""
    }
    val canSubmit = name.isNotBlank() && error.isEmpty() && !submitting
    fun create() {
        if (!canSubmit) return
        submitting = true
        val requestedName = name
        val createFolder = folder
        val openCreatedFolder = openFolder
        tab.scope.launch {
            if (tab.activeFolder.findFile(requestedName) != null) {
                globalClass.showMsg(R.string.similar_file_exists)
                submitting = false
                return@launch
            }
            onDismissRequest()
            tab.isLoading = true
            if (createFolder) {
                tab.activeFolder.createSubFolder(requestedName) { created ->
                    tab.isLoading = false
                    if (created == null) globalClass.showMsg(R.string.failed_to_create_folder)
                    else tab.onNewFileCreated(created, openCreatedFolder)
                }
            } else {
                tab.activeFolder.createSubFile(requestedName) { created ->
                    tab.isLoading = false
                    if (created == null) globalClass.showMsg(R.string.failed_to_create_file)
                    else tab.onNewFileCreated(created)
                }
            }
        }
    }
    TextEntryTopSheet(stringResource(R.string.create_new), onDismissRequest) { focus ->
        SheetEntryField(name, { name = it }, stringResource(R.string.name), focus, error, canSubmit, ::create)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = folder, onClick = { folder = true }, label = { Text(stringResource(R.string.folder)) })
            FilterChip(selected = !folder, onClick = { folder = false }, label = { Text(stringResource(R.string.file)) })
        }
        if (folder) CheckableText(checked = openFolder, onCheckedChange = { openFolder = it }) {
            Text(stringResource(R.string.open_created_folder))
        }
        Button(onClick = ::create, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.create))
        }
    }
}
