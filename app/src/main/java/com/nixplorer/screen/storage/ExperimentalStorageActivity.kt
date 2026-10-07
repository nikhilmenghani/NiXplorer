package com.nixplorer.screen.storage

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExperimentalStorageActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
    }
    override fun onPermissionGranted() {
        setContent { FileExplorerTheme { ExperimentalStorageScreen { finish() } } }
    }
}

private fun StorageCategory.label(): Int = when (this) {
    StorageCategory.VIDEOS -> R.string.storage_large_videos
    StorageCategory.IMAGES -> R.string.storage_large_images
    StorageCategory.DOCUMENTS -> R.string.storage_large_documents
    StorageCategory.APKS -> R.string.storage_large_apks
    StorageCategory.AUDIO -> R.string.storage_large_audio
    StorageCategory.ARCHIVES -> R.string.storage_large_archives
    StorageCategory.OTHER -> R.string.storage_large_other
}
private fun StorageCategory.icon(): ImageVector = when (this) {
    StorageCategory.VIDEOS -> Icons.Rounded.Movie
    StorageCategory.IMAGES -> Icons.Rounded.Image
    StorageCategory.DOCUMENTS -> Icons.Rounded.Description
    StorageCategory.APKS -> Icons.Rounded.Android
    StorageCategory.AUDIO -> Icons.Rounded.MusicNote
    StorageCategory.ARCHIVES -> Icons.Rounded.Inventory2
    StorageCategory.OTHER -> Icons.Rounded.InsertDriveFile
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExperimentalStorageScreen(close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<StorageDevice>>(emptyList()) }
    var volume by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<StorageCategory?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    var scanEnabled by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var count by remember { mutableLongStateOf(0) }
    var rootKey by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<FileStorageReport?>(null) }
    var capacity by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var apps by remember { mutableStateOf<List<AppStorageEntry>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(context.hasStorageUsageAccess()) }
    var systemApps by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { access = context.hasStorageUsageAccess(); revision++ }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        devices = withContext(Dispatchers.IO) { StorageProvider.getStorageDevices(context).filter { it.contentHolder is LocalFileHolder } }
    }
    LaunchedEffect(devices, volume, revision, scanEnabled) {
        if (devices.isEmpty() || !scanEnabled) return@LaunchedEffect
        checking = true
        error = null
        val root = (devices[volume.coerceAtMost(devices.lastIndex)].contentHolder as LocalFileHolder).file
        if (rootKey != root.absolutePath) { report = StorageAnalysisCache.peek(root); rootKey = root.absolutePath }
        try {
            capacity = withContext(Dispatchers.IO) { StatFs(root.absolutePath).let { it.totalBytes to it.availableBytes } }
            val forced = force
            force = false
            report = StorageAnalysisCache.load(root, context.cacheDir, forced,
                onCached = { report = it }, onScanning = { scanning = true; count = 0 }, onProgress = { count = it })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = context.getString(R.string.storage_scan_failed) }
        finally { checking = false; scanning = false }
    }
    LaunchedEffect(page, revision, access) {
        if (page != 2) return@LaunchedEffect
        loadingApps = true
        try { apps = loadAppStorage(context) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = context.getString(R.string.storage_scan_failed) }
        finally { loadingApps = false }
    }
    fun back() { if (selected != null) selected = null else close() }
    BackHandler(selected != null) { selected = null }
    fun manage(app: AppStorageEntry) {
        invalidateAppStorage()
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.TopCenter) {
        Scaffold(Modifier.widthIn(max = 920.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(title = { Text(selected?.let { stringResource(it.label()) } ?: stringResource(R.string.analysis_title),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        IconButton(onClick = { force = true; scanEnabled = true; invalidateAppStorage(); revision++ }) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_analysis))
                        }
                    })
            },
            bottomBar = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.files, R.string.analysis_folders, R.string.apps).forEachIndexed { index, label ->
                        Surface(onClick = { page = index; selected = null }, modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (page == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, if (page == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(when (index) { 0 -> Icons.Rounded.Description; 1 -> Icons.Rounded.Folder; else -> Icons.Rounded.Apps }, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, fontWeight = if (page == index) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (devices.size > 1 && page != 2) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        devices.forEachIndexed { index, device -> FilterChip(selected = volume == index,
                            onClick = { volume = index; selected = null; scanEnabled = true }, label = { Text(device.title) }) }
                    }
                }
                if (page != 2 && selected == null) item {
                    StorageCategoryCard(capacity, report, devices.getOrNull(volume)?.title.orEmpty(), checking) { page = 0; selected = it }
                }
                if (checking && page != 2) item {
                    if (scanning) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.storage_scanning, count), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scanEnabled = false }) { Text(stringResource(R.string.cancel)) }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (!scanEnabled && report == null) item { AnalysisNotice(stringResource(R.string.storage_scan_stopped)) }
                error?.let { item { AnalysisNotice(it) } }
                if (page != 2 && report != null) {
                    val data = report!!
                    if (data.skippedFolders > 0) item { AnalysisNotice(stringResource(R.string.analysis_partial, data.skippedFolders)) }
                    val entries = if (page == 1) data.folders else selected?.let { category -> data.categories.firstOrNull { it.category == category }?.largest }
                    if (entries != null) {
                        item {
                            Text(stringResource(if (page == 1) R.string.largest_folders else R.string.storage_category_largest), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.storage_ranked_count, entries.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (entries.isEmpty()) item { AnalysisNotice(stringResource(R.string.storage_no_files)) }
                        items(entries, key = { it.file.absolutePath }) { entry ->
                            StorageUsageRow(title = entry.file.name, subtitle = entry.file.absolutePath.removePrefix(rootKey.orEmpty()).trimStart('/'),
                                size = if (entry.complete) entry.bytes.toFormattedSize() else stringResource(R.string.folder_size_at_least, entry.bytes.toFormattedSize()),
                                fraction = entry.bytes.toFloat() / (entries.firstOrNull()?.bytes ?: 1).coerceAtLeast(1),
                                icon = { Icon(storageFileIcon(entry.file, page == 1), null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary) },
                                openLabel = stringResource(R.string.open), onOpen = {
                                    if (page == 1) { globalClass.mainActivityManager.addTabAndSelect(FilesTab(LocalFileHolder(entry.file))); close() }
                                    else LocalFileHolder(entry.file).open(context, false, false, null)
                                }, removeLabel = stringResource(R.string.delete), onRemove = { deleteTarget = entry.file })
                        }
                    }
                }
                if (page == 2) {
                    if (!access) item {
                        AnalysisNotice(stringResource(R.string.storage_usage_access_info), Icons.Rounded.Security) {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text(stringResource(R.string.grant_usage_access)) }
                        }
                    }
                    val visible = apps.filter { systemApps || !it.system }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.analysis_largest_apps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(visible.sumOf { it.total }.toFormattedSize(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                            }
                            FilterChip(systemApps, { systemApps = !systemApps }, label = { Text(stringResource(R.string.analysis_system_apps)) })
                        }
                    }
                    if (loadingApps) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    items(visible, key = { it.packageName }) { app ->
                        StorageUsageRow(app.name, app.packageName, app.total.toFormattedSize(),
                            app.total.toFloat() / (visible.firstOrNull()?.total ?: 1).coerceAtLeast(1), icon = { AppStorageIcon(app.packageName) },
                            openLabel = stringResource(R.string.manage_storage), onOpen = { manage(app) },
                            removeLabel = if (!app.system && app.packageName != context.packageName) stringResource(R.string.uninstall) else null,
                            onRemove = if (!app.system && app.packageName != context.packageName) ({
                                invalidateAppStorage()
                                runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }.onFailure { manage(app) }
                                Unit
                            }) else null,
                            detail = if (app.measured) stringResource(R.string.app_storage_breakdown, app.appBytes.toFormattedSize(),
                                (app.dataBytes - app.cacheBytes).coerceAtLeast(0).toFormattedSize(), app.cacheBytes.toFormattedSize()) else stringResource(R.string.app_storage_apk_only))
                    }
                }
                item { Text(stringResource(R.string.storage_cleanup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    deleteTarget?.let { target ->
        val tab = remember(target) {
            FilesTab(LocalFileHolder(target.parentFile ?: target)).apply {
                val holder = LocalFileHolder(target)
                selectedFiles[holder.uniquePath] = holder
            }
        }
        DeleteConfirmationDialog(true, tab, onConfirmed = {
            scope.launch {
                repeat(120) { if (!withContext(Dispatchers.IO) { target.exists() }) { revision++; return@launch }; delay(1000) }
                revision++
            }
        }, onDismissRequest = { deleteTarget = null })
    }
}

@Composable
private fun StorageCategoryCard(capacity: Pair<Long, Long>?, report: FileStorageReport?, title: String,
    checking: Boolean, open: (StorageCategory) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title.ifBlank { stringResource(R.string.storage) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            capacity?.let { (total, free) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.storage_available, free.toFormattedSize()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.storage_device_total, total.toFormattedSize()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(report?.bytes?.toFormattedSize() ?: "—", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.storage_scanned_files), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LinearProgressIndicator(progress = { ((total - free).toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp))
            }
            Text(stringResource(R.string.storage_manage_categories), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.storage_category_totals_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints {
                val columns = if (maxWidth >= 540.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StorageCategory.entries.chunked(columns).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            group.forEach { category ->
                                val data = report?.categories?.firstOrNull { it.category == category }
                                Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
                                    Row(Modifier.padding(start = 10.dp, top = 5.dp, end = 7.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(category.icon(), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(9.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(stringResource(category.label()), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(if (data == null) "—" else stringResource(R.string.storage_category_summary, data.bytes.toFormattedSize(), data.count),
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        OutlinedButton(onClick = { open(category) }, enabled = data?.largest?.isNotEmpty() == true,
                                            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text(stringResource(R.string.open)) }
                                    }
                                }
                            }
                            if (group.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
