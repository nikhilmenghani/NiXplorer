package com.nixplorer.common.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun TextEntryTopSheet(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(FocusRequester) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var visible by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(Unit) {
            visible = true
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onDismissRequest
        )) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically { -it / 2 } + fadeIn(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().navigationBarsPadding().imePadding()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}
                    ),
                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        content(focusRequester)
                    }
                }
            }
        }
    }
}

@Composable
fun SheetActionButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable RowScope.() -> Unit
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(12.dp), content = content)
}

@Composable
fun SheetEntryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    focusRequester: FocusRequester,
    error: String = "",
    canSubmit: Boolean,
    onSubmit: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        singleLine = true,
        isError = error.isNotEmpty(),
        supportingText = if (error.isNotEmpty()) ({ Text(error) }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit() })
    )
}
