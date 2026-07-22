package com.nixplorer.common.ui

import androidx.compose.runtime.Composable

@Composable
fun Isolate(content: @Composable () -> Unit) {
    content()
}