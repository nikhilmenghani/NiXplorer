package com.nixplorer.screen.main.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.ui.SheetEntryField
import com.nixplorer.common.ui.TextEntryTopSheet
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import java.io.File

@Composable
fun JumpToPathDialog(show: Boolean, onDismiss: () -> Unit = {}) {
    if (!show) return
    val context = LocalContext.current
    var path by remember { mutableStateOf("") }
    val valid = path.isNotBlank() && FilesTab.isValidLocalPath(path)
    fun open() {
        if (!valid) return
        globalClass.mainActivityManager.jumpToFile(LocalFileHolder(File(path)), context)
        onDismiss()
    }
    TextEntryTopSheet(stringResource(R.string.jump_to_path), onDismiss) { focus ->
        SheetEntryField(path, { path = it }, stringResource(R.string.destination_path), focus,
            error = if (path.isNotBlank() && !valid) stringResource(R.string.invalid_path) else "",
            canSubmit = valid, onSubmit = ::open)
        Button(onClick = ::open, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open))
        }
    }
}
