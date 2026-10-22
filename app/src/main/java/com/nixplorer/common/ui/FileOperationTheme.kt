package com.nixplorer.common.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Expressive motion and controls, using the file browser's existing dynamic palette and type. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FileOperationTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
