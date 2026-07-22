package com.nixplorer.screen.preferences

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nixplorer.R
import com.nixplorer.base.BaseActivity
import com.nixplorer.common.ui.SafeSurface
import com.nixplorer.screen.preferences.ui.AppInfoContainer
import com.nixplorer.screen.preferences.ui.AppearanceContainer
import com.nixplorer.screen.preferences.ui.BehaviorContainer
import com.nixplorer.screen.preferences.ui.FileListContainer
import com.nixplorer.screen.preferences.ui.FileOperationContainer
import com.nixplorer.screen.preferences.ui.RecentFilesContainer
import com.nixplorer.screen.preferences.ui.SingleChoiceDialog
import com.nixplorer.screen.preferences.ui.TextEditorContainer
import com.nixplorer.theme.FileExplorerTheme

class PreferencesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onPermissionGranted() {
        setContent {
            FileExplorerTheme {
                SafeSurface {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .background(color = colorScheme.surfaceContainer)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = null
                            )
                        }
                        Column(
                            Modifier.weight(1f)
                        ) {
                            Text(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(id = R.string.preferences),
                                fontSize = 21.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    SingleChoiceDialog()

                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AppearanceContainer()
                        FileListContainer()
                        FileOperationContainer()
                        BehaviorContainer()
                        RecentFilesContainer()
                        TextEditorContainer()
                        AppInfoContainer()
                    }
                }
            }
        }
    }
}