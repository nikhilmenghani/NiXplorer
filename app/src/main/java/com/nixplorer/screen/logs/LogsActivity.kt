package com.nixplorer.screen.logs

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nixplorer.base.BaseActivity
import com.nixplorer.screen.logs.ui.LogsScreen
import com.nixplorer.theme.FileExplorerTheme

class LogsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
    }

    override fun onPermissionGranted() {
        setContent {
            FileExplorerTheme {
                LogsScreen { onBackPressedDispatcher.onBackPressed() }
            }
        }
    }
}