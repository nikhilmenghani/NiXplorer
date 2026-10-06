package com.nixplorer.common.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetDialog(
    onDismissRequest: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = { onDismissRequest() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ) {
        content()
    }
}
