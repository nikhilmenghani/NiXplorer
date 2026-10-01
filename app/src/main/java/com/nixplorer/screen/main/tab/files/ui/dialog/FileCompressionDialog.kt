package com.nixplorer.screen.main.tab.files.ui.dialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.isValidAsFileName
import com.nixplorer.common.ui.SheetEntryField
import com.nixplorer.common.ui.TextEntryTopSheet
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.task.CompressTaskParameters
import java.io.File

@Composable
fun FileCompressionDialog(show: Boolean, tab: FilesTab, onDismissRequest: () -> Unit) {
    if (!show) return
    var name by remember { mutableStateOf("${tab.activeFolder.displayName}.zip") }
    val names = tab.activeFolderContent.map { it.displayName }
    val error = when {
        name.isBlank() -> ""
        !name.isValidAsFileName() -> stringResource(R.string.invalid_file_name)
        name in names -> stringResource(R.string.similar_file_exists)
        else -> ""
    }
    val valid = name.isNotBlank() && error.isEmpty()
    fun create() {
        if (!valid) return
        onDismissRequest()
        globalClass.taskManager.runTask(tab.compressTaskHolder!!.id,
            CompressTaskParameters(File((tab.activeFolder as LocalFileHolder).file, name).absolutePath))
    }
    TextEntryTopSheet(stringResource(R.string.create_archive), onDismissRequest) { focus ->
        SheetEntryField(name, { name = it }, stringResource(R.string.name), focus, error, valid, ::create)
        Button(onClick = ::create, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.create))
        }
        TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.minimize))
        }
    }
}
