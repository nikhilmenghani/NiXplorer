package com.nixplorer.screen.storage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.base.BaseActivity
import com.nixplorer.common.toFormattedSize
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.holder.StorageDevice
import com.nixplorer.screen.main.tab.files.provider.StorageProvider
import com.nixplorer.screen.main.tab.files.ui.dialog.DeleteConfirmationDialog
import com.nixplorer.theme.FileExplorerTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageAnalyzerActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
    }
    override fun onPermissionGranted() {
        setContent { FileExplorerTheme { StorageAnalyzerScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageAnalyzerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<StorageDevice>>(emptyList()) }
    var volume by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var folders by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var appRefresh by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var scanRequested by remember { mutableStateOf(true) }
    var count by remember { mutableLongStateOf(0) }
    var report by remember { mutableStateOf<FileStorageReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<AppStorageEntry>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }
    var usageAccess by remember { mutableStateOf(context.hasStorageUsageAccess()) }
    var systemApps by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var capacity by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(Unit) {
        devices = withContext(Dispatchers.IO) { StorageProvider.getStorageDevices(context).filter { it.contentHolder is LocalFileHolder } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageAccess = context.hasStorageUsageAccess()
                appRefresh++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(devices, volume, refresh, scanRequested) {
        if (devices.isEmpty() || !scanRequested) { scanning = false; return@LaunchedEffect }
        scanning = true
        report = null
        error = null
        count = 0
        try {
            val root = (devices[volume].contentHolder as LocalFileHolder).file
            capacity = withContext(Dispatchers.IO) { StatFs(root.absolutePath).let { it.totalBytes to it.availableBytes } }
            report = analyzeFiles(root) { count = it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = context.getString(R.string.storage_scan_failed) }
        finally { scanning = false }
    }
    LaunchedEffect(page, appRefresh) {
        if (page != 1) return@LaunchedEffect
        loadingApps = true
        try { apps = loadAppStorage(context) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = context.getString(R.string.storage_scan_failed) }
        finally { loadingApps = false }
    }
    fun browse(file: File) {
        globalClass.mainActivityManager.addTabAndSelect(FilesTab(LocalFileHolder(file)))
        onBack()
    }
    fun settings(app: AppStorageEntry) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.analyze_storage)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(onClick = { scanRequested = true; refresh++; appRefresh++ }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_analysis)) } })
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = page) {
                Tab(selected = page == 0, onClick = { page = 0 }, text = { Text(stringResource(R.string.files)) })
                Tab(selected = page == 1, onClick = { page = 1 }, text = { Text(stringResource(R.string.apps)) })
            }
            if (page == 0) {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices.indices.toList()) { index -> FilterChip(selected = volume == index,
                        onClick = { volume = index; scanRequested = true }, label = { Text(devices[index].title) }) }
                }
                capacity?.let { (total, free) ->
                    Text(stringResource(R.string.storage_capacity, (total - free).toFormattedSize(), free.toFormattedSize()), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { ((total - free).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                if (scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.storage_scanning, count), Modifier.weight(1f))
                        TextButton(onClick = { scanRequested = false }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !folders, onClick = { folders = false }, label = { Text(stringResource(R.string.largest_files)) })
                    FilterChip(selected = folders, onClick = { folders = true }, label = { Text(stringResource(R.string.largest_folders)) })
                }
                report?.let { data ->
                    Text(stringResource(R.string.storage_visible_total, data.bytes.toFormattedSize(), data.fileCount), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.storage_cleanup_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    if (data.skippedFolders > 0) Text(stringResource(R.string.storage_partial_scan, data.skippedFolders), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    if (folders) Text(stringResource(R.string.storage_folder_overlap), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    val entries = if (folders) data.folders else data.files
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entries.isEmpty()) item { Text(stringResource(R.string.storage_no_files)) }
                        items(entries, key = { it.file.absolutePath }) { entry ->
                            AnalysisCard(entry.file.name, if (entry.complete) entry.bytes.toFormattedSize() else stringResource(R.string.folder_size_at_least, entry.bytes.toFormattedSize()), entry.file.absolutePath) {
                                TextButton(onClick = { browse(entry.file) }) { Text(stringResource(R.string.open)) }
                                TextButton(onClick = { deleteTarget = entry.file }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                if (!scanning && report == null && error == null) Text(stringResource(R.string.storage_scan_stopped), Modifier.padding(16.dp))
            } else {
                if (!usageAccess) {
                    Text(stringResource(R.string.storage_usage_access_info), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Text(stringResource(R.string.grant_usage_access)) }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.include_system_apps), Modifier.weight(1f))
                    Switch(checked = systemApps, onCheckedChange = { systemApps = it })
                }
                if (loadingApps) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(apps.filter { systemApps || !it.system }, key = { it.packageName }) { app ->
                        val details = if (app.measured) context.getString(R.string.app_storage_breakdown,
                            app.appBytes.toFormattedSize(), (app.dataBytes - app.cacheBytes).coerceAtLeast(0).toFormattedSize(), app.cacheBytes.toFormattedSize())
                            else context.getString(R.string.app_storage_apk_only)
                        AnalysisCard(app.name, app.total.toFormattedSize(), "${app.packageName}\n$details") {
                            TextButton(onClick = { settings(app) }) { Text(stringResource(R.string.manage_storage)) }
                            if (!app.system && app.packageName != context.packageName) TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }.onFailure { settings(app) }
                            }) { Text(stringResource(R.string.uninstall), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        }
    }
    deleteTarget?.let { file ->
        val deleteTab = remember(file) {
            FilesTab(LocalFileHolder(file.parentFile ?: file)).apply {
                val holder = LocalFileHolder(file)
                selectedFiles[holder.uniquePath] = holder
            }
        }
        DeleteConfirmationDialog(true, deleteTab) { deleteTarget = null }
    }
}

@Composable
private fun AnalysisCard(title: String, size: String, subtitle: String, actions: @Composable RowScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(size, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        }
    }
}
