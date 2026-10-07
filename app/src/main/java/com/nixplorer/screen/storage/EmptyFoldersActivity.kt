package com.nixplorer.screen.storage

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nixplorer.R
import com.nixplorer.base.BaseActivity
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.holder.StorageDevice
import com.nixplorer.screen.main.tab.files.misc.StorageDeviceType
import com.nixplorer.screen.main.tab.files.provider.StorageProvider
import com.nixplorer.theme.FileExplorerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmptyFoldersActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
    }
    override fun onPermissionGranted() {
        setContent { FileExplorerTheme { EmptyFoldersScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyFoldersScreen(close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<StorageDevice>>(emptyList()) }
    var volume by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var scanEnabled by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var checked by remember { mutableLongStateOf(0) }
    var report by remember { mutableStateOf<EmptyFolderReport?>(null) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var processed by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<EmptyFolderCleanup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        devices = withContext(Dispatchers.IO) {
            StorageProvider.getStorageDevices(context).filter { it.contentHolder is LocalFileHolder && it.type != StorageDeviceType.ROOT }
        }
    }
    LaunchedEffect(devices, volume, revision, scanEnabled) {
        if (devices.isEmpty() || !scanEnabled) return@LaunchedEffect
        scanning = true
        report = null
        selection = emptySet()
        checked = 0
        error = null
        try {
            val root = (devices[volume].contentHolder as LocalFileHolder).file
            report = findEmptyFolders(root) { checked = it }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = context.getString(R.string.empty_folders_failed) }
        finally { scanning = false }
    }
    BackHandler(cleaning) { }
    val folders = report?.folders.orEmpty()
    val selected = folders.filter { it.file.absolutePath in selection }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.TopCenter) {
        Scaffold(Modifier.widthIn(max = 920.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.empty_folders)) },
                    navigationIcon = { IconButton(enabled = !cleaning, onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = { IconButton(enabled = !cleaning, onClick = { scanEnabled = true; result = null; revision++ }) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_analysis))
                    } })
            },
            bottomBar = {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    if (cleaning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.empty_folders_cleaning, processed), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { confirm = true }, enabled = selected.isNotEmpty() && !scanning && !cleaning,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.DeleteSweep, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.empty_folders_delete_selected, selected.size))
                    }
                }
            }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { AnalysisNotice(stringResource(R.string.empty_folders_explanation), Icons.Rounded.FolderOff) }
                if (devices.size > 1) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        devices.forEachIndexed { index, device ->
                            FilterChip(selected = volume == index, enabled = !cleaning, onClick = { volume = index; scanEnabled = true; result = null }, label = { Text(device.title) })
                        }
                    }
                }
                if (scanning) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.empty_folders_scanning, checked), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { scanEnabled = false }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                if (!scanning && !scanEnabled && report == null) item { AnalysisNotice(stringResource(R.string.storage_scan_stopped)) }
                result?.let { summary -> item { AnalysisNotice(stringResource(R.string.empty_folders_result, summary.removed, summary.skipped), Icons.Rounded.CheckCircle) } }
                error?.let { item { AnalysisNotice(it, Icons.Rounded.ErrorOutline) } }
                report?.let { data ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.empty_folders_found, data.folders.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.empty_folders_total, data.folders.sumOf { it.folderCount }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (folders.isNotEmpty()) TextButton(enabled = !cleaning, onClick = {
                                selection = if (selection.size == folders.size) emptySet() else folders.map { it.file.absolutePath }.toSet()
                            }) { Text(stringResource(if (selection.size == folders.size) R.string.empty_folders_select_none else R.string.select_all)) }
                        }
                    }
                    if (data.skipped > 0) item { AnalysisNotice(stringResource(R.string.empty_folders_skipped, data.skipped)) }
                    if (data.folders.isEmpty()) item { AnalysisNotice(stringResource(R.string.empty_folders_none), Icons.Rounded.CheckCircle) }
                    items(data.folders, key = { it.file.absolutePath }) { folder ->
                        val path = folder.file.absolutePath
                        Surface(onClick = { selection = if (path in selection) selection - path else selection + path }, enabled = !cleaning,
                            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Checkbox(checked = path in selection, onCheckedChange = null, enabled = !cleaning)
                                Icon(Icons.Rounded.FolderOff, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(folder.file.name, style = MaterialTheme.typography.titleSmall)
                                    Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.empty_folders_includes, folder.folderCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.empty_folders_confirm_title)) },
        text = { Text(stringResource(R.string.empty_folders_confirm_message, selected.size, selected.sumOf { it.folderCount })) },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                val targets = selected.toList()
                val root = (devices[volume].contentHolder as LocalFileHolder).file
                cleaning = true
                processed = 0
                scope.launch {
                    try {
                        result = cleanEmptyFolders(root, targets,
                            removeDirectory = ::removeEmptyDirectory,
                            onProgress = { processed = it })
                        report = findEmptyFolders(root)
                        selection = emptySet()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = context.getString(R.string.empty_folders_failed) }
                    finally { cleaning = false }
                }
            }) { Text(stringResource(R.string.delete)) }
        })
}
