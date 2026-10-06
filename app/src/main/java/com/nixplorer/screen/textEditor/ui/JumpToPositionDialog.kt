package com.nixplorer.screen.textEditor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.asCodeEditorCursorCoordinates
import com.nixplorer.common.ui.SheetEntryField
import com.nixplorer.common.ui.TextEntryTopSheet
import com.nixplorer.common.ui.SheetActionButton
import io.github.rosemoe.sora.widget.CodeEditor

@Composable
fun JumpToPositionDialog(codeEditor: CodeEditor, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val position = input.asCodeEditorCursorCoordinates()
    val valid = input.isNotBlank() && position.first > 0 && position.second > 0
    fun go() {
        if (!valid) return
        runCatching { codeEditor.setSelection(position.first - 1, position.second - 1) }
            .onSuccess { onDismiss() }
            .onFailure { globalClass.showMsg(R.string.invalid_position) }
    }
    TextEntryTopSheet(stringResource(R.string.jump_to_position), onDismiss) { focus ->
        SheetEntryField(input, { input = it }, stringResource(R.string.jump_to_position_label), focus,
            error = if (input.isNotBlank() && !valid) stringResource(R.string.invalid_position) else "",
            canSubmit = valid, onSubmit = ::go)
        SheetActionButton(onClick = ::go, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.go))
        }
    }
}
