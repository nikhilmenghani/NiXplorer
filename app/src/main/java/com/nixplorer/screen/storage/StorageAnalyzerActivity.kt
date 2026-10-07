package com.nixplorer.screen.storage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    var showNotes by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var forceScan by remember { mutableStateOf(false) }
    var resumeCheck by remember { mutableIntStateOf(0) }
    var appRefresh by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var reportRoot by remember { mutableStateOf<String?>(null) }
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
                resumeCheck++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(devices, volume, refresh, resumeCheck, scanRequested) {
        if (devices.isEmpty() || !scanRequested) { scanning = false; return@LaunchedEffect }
        scanning = false
        checking = true
        error = null
        count = 0
        try {
            val root = (devices[volume].contentHolder as LocalFileHolder).file
            if (reportRoot != root.absolutePath) {
                report = StorageAnalysisCache.peek(root)
                reportRoot = root.absolutePath
            }
            capacity = withContext(Dispatchers.IO) { StatFs(root.absolutePath).let { it.totalBytes to it.availableBytes } }
            val force = forceScan
            forceScan = false
            report = StorageAnalysisCache.load(root, context.cacheDir, force = force,
                onCached = { report = it },
                onScanning = { scanning = true }, onProgress = { count = it })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = context.getString(R.string.storage_scan_failed) }
        finally { scanning = false; checking = false }
    }
    LaunchedEffect(page, appRefresh) {
        if (page != 2) return@LaunchedEffect
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
        invalidateAppStorage()
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.TopCenter) {
        Scaffold(
            modifier = Modifier.widthIn(max = 920.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.analysis_title), style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        IconButton(onClick = { context.startActivity(Intent(context, ExperimentalStorageActivity::class.java)) }) {
                            Icon(Icons.Rounded.Science, stringResource(R.string.experimental_storage))
                        }
                        IconButton(onClick = { showNotes = !showNotes }) { Icon(Icons.Rounded.Info, stringResource(R.string.analysis_about)) }
                        IconButton(onClick = { invalidateAppStorage(); usageAccess = context.hasStorageUsageAccess(); forceScan = true; scanRequested = true; refresh++; appRefresh++ }) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_analysis))
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    val labels = listOf(R.string.files, R.string.analysis_folders, R.string.apps)
                    labels.forEachIndexed { index, label ->
                        SegmentedButton(selected = page == index, onClick = { page = index },
                            shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                            icon = {
                                Icon(when (index) {
                                    0 -> Icons.Rounded.Description
                                    1 -> Icons.Rounded.Folder
                                    else -> Icons.Rounded.Apps
                                }, null, Modifier.size(18.dp))
                            }) { Text(stringResource(label)) }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (showNotes) item {
                        AnalysisNotice(stringResource(R.string.storage_cleanup_hint) + "\n" + stringResource(R.string.storage_folder_overlap))
                    }
                    if (page != 2) {
                        if (devices.size > 1) item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(devices.indices.toList()) { index -> FilterChip(selected = volume == index,
                                    onClick = { volume = index; scanRequested = true }, label = { Text(devices[index].title) }) }
                            }
                        }
                        capacity?.let { (total, free) -> item {
                            StorageCapacityCard(total, free, devices.getOrNull(volume)?.title.orEmpty())
                        } }
                        if (checking && !scanning) item {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (scanning) item {
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.storage_scanning, count), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        TextButton(onClick = { scanRequested = false }) { Text(stringResource(R.string.cancel)) }
                                    }
                                }
                            }
                        }
                        report?.let { data ->
                            val entries = if (page == 1) data.folders else data.files
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(if (page == 1) R.string.largest_folders else R.string.largest_files),
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.analysis_file_summary, data.fileCount, data.bytes.toFormattedSize()),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (data.skippedFolders > 0) item { AnalysisNotice(stringResource(R.string.analysis_partial, data.skippedFolders)) }
                            if (entries.isEmpty()) item { AnalysisNotice(stringResource(R.string.storage_no_files), Icons.Rounded.FolderOpen) }
                            items(entries, key = { "${page}:${it.file.absolutePath}" }) { entry ->
                                val root = (devices[volume].contentHolder as LocalFileHolder).file.absolutePath
                                val location = entry.file.absolutePath.removePrefix(root).trimStart('/')
                                StorageUsageRow(
                                    title = entry.file.name,
                                    subtitle = location,
                                    size = if (entry.complete) entry.bytes.toFormattedSize() else stringResource(R.string.folder_size_at_least, entry.bytes.toFormattedSize()),
                                    fraction = entry.bytes.toFloat() / (entries.firstOrNull()?.bytes ?: 1L).coerceAtLeast(1),
                                    icon = { Icon(storageFileIcon(entry.file, page == 1), null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary) },
                                    openLabel = stringResource(R.string.open), onOpen = { browse(entry.file) },
                                    removeLabel = stringResource(R.string.delete), onRemove = { deleteTarget = entry.file }
                                )
                            }
                        }
                        if (!scanRequested && !checking && report == null && error == null) item { AnalysisNotice(stringResource(R.string.storage_scan_stopped)) }
                    } else {
                        if (!usageAccess) item {
                            AnalysisNotice(stringResource(R.string.storage_usage_access_info), Icons.Rounded.Security) {
                                FilledTonalButton(onClick = {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                                }) { Text(stringResource(R.string.grant_usage_access)) }
                            }
                        }
                        val visibleApps = apps.filter { systemApps || !it.system }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.analysis_largest_apps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.analysis_apps_summary, visibleApps.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FilterChip(selected = systemApps, onClick = { systemApps = !systemApps }, label = { Text(stringResource(R.string.analysis_system_apps)) })
                            }
                        }
                        if (loadingApps) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (!loadingApps && visibleApps.isEmpty()) item { AnalysisNotice(stringResource(R.string.analysis_no_apps), Icons.Rounded.Apps) }
                        items(visibleApps, key = { it.packageName }) { app ->
                            val details = if (app.measured) context.getString(R.string.app_storage_breakdown,
                                app.appBytes.toFormattedSize(), (app.dataBytes - app.cacheBytes).coerceAtLeast(0).toFormattedSize(), app.cacheBytes.toFormattedSize())
                                else context.getString(R.string.app_storage_apk_only)
                            StorageUsageRow(
                                title = app.name, subtitle = app.packageName, size = app.total.toFormattedSize(),
                                fraction = app.total.toFloat() / (visibleApps.firstOrNull()?.total ?: 1L).coerceAtLeast(1),
                                icon = { AppStorageIcon(app.packageName) }, detail = details,
                                openLabel = stringResource(R.string.manage_storage), onOpen = { settings(app) },
                                removeLabel = if (!app.system && app.packageName != context.packageName) stringResource(R.string.uninstall) else null,
                                onRemove = if (!app.system && app.packageName != context.packageName) ({
                                    invalidateAppStorage()
                                    runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }.onFailure { settings(app) }
                                    Unit
                                }) else null
                            )
                        }
                    }
                    error?.let { item { AnalysisNotice(it, Icons.Rounded.ErrorOutline) } }
                }
            }
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
