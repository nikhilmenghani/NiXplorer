package com.nixplorer.screen.share

import android.app.Application
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nixplorer.screen.main.tab.files.ui.FilesTabContentView
import com.nixplorer.R
import com.nixplorer.base.BaseActivity
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.task.SafeFileOperations
import com.nixplorer.theme.FileExplorerTheme
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

class SaveSharedFilesActivity : BaseActivity() {
    private lateinit var model: SaveSharedFilesModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[SaveSharedFilesModel::class.java]
        if (!model.initialized) {
            model.initialized = true
            try {
                require(intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)
                @Suppress("DEPRECATION")
                val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                val sources = streams.ifEmpty {
                    intent.clipData?.let { data ->
                        require(data.itemCount <= 1000)
                        (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri }
                    }.orEmpty()
                }.distinct()
                require(sources.isNotEmpty() && sources.size <= 1000 && sources.all { it.scheme == "content" })
                model.sources = sources
            } catch (_: Exception) {
                model.error = getString(R.string.save_shared_invalid)
            }
        }
        checkPermissions()
    }
    override fun onPermissionGranted() {
        setContent { FileExplorerTheme { SaveSharedScreen(model, ::finish) } }
    }
}

class SaveSharedFilesModel(application: Application) : AndroidViewModel(application) {
    var initialized = false
    var sources = emptyList<Uri>()
    private val saved = mutableSetOf<Uri>()
    var busy by mutableStateOf(false)
    var completed by mutableIntStateOf(0)
    var current by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    private var copyJob: Job? = null
    fun cancel() { copyJob?.cancel() }
    fun save(folder: File) {
        if (busy || sources.isEmpty() || completed == sources.size) return
        busy = true
        error = null
        copyJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val failures = mutableListOf<String>()
            try {
                for ((index, uri) in sources.withIndex()) {
                    if (uri in saved) continue
                    ensureActive()
                    var name = "shared-file-${index + 1}"
                    try {
                        withContext(Dispatchers.IO) {
                            val resolver = context.contentResolver
                            var expected: Long? = null
                            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (n >= 0 && !cursor.isNull(n)) name = sharedFileName(cursor.getString(n), name)
                                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (size >= 0 && !cursor.isNull(size)) expected = cursor.getLong(size).takeIf { it >= 0 }
                                }
                            }
                            withContext(Dispatchers.Main) { current = name }
                            val destinationName = availableSharedName(folder, name)
                            val job = currentCoroutineContext().job
                            SafeFileOperations.stagedStream(folder, destinationName, overwrite = false,
                                maxBytes = Long.MAX_VALUE, cancelled = { !job.isActive }, expectedBytes = expected,
                                input = { resolver.openInputStream(uri) ?: throw IOException("Cannot read shared file") })
                            // Publication succeeded. Record it even if cancellation arrives now.
                            withContext(NonCancellable + Dispatchers.Main) { saved.add(uri); completed = saved.size }
                            runCatching { MediaScannerConnection.scanFile(context, arrayOf(folder.resolve(destinationName).path), null, null) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures.add("$name: ${e.message ?: "Unable to save"}") }
                }
            } catch (_: CancellationException) {
                error = context.getString(R.string.save_shared_cancelled)
            } finally {
                if (failures.isNotEmpty()) error = failures.take(20).joinToString("\n")
                current = ""
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSharedScreen(model: SaveSharedFilesModel, close: () -> Unit) {
    var folderPath by rememberSaveable { mutableStateOf(Environment.getExternalStorageDirectory().path) }
    val tab = remember {
        com.nixplorer.screen.main.tab.files.FilesTab(LocalFileHolder(File(folderPath)))
    }
    LaunchedEffect(tab) { tab.onTabStarted() }
    LaunchedEffect(tab) {
        snapshotFlow { tab.activeFolder.uniquePath }.collect { folderPath = it }
    }
    DisposableEffect(tab) { onDispose { tab.scope.cancel() } }
    fun back() {
        if (model.busy) model.cancel()
        else if (!tab.onBackPressed()) close()
    }
    BackHandler(onBack = ::back)
    Scaffold(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), topBar = {
        TopAppBar(title = {
            Column {
                Text(stringResource(R.string.save_to_nixplorer), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.save_shared_count, model.completed, model.sources.size),
                    style = MaterialTheme.typography.bodySmall)
            }
        }, navigationIcon = { IconButton(onClick = ::back) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
        } })
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (model.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(model.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = model::cancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            } else if (model.sources.isNotEmpty() && model.completed == model.sources.size) {
                Button(onClick = close, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) {
                    Text(stringResource(R.string.close))
                }
            } else {
                Button(onClick = { (tab.activeFolder as? LocalFileHolder)?.file?.let(model::save) },
                    modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    enabled = !tab.isLoading && tab.folderReadError == null &&
                        tab.activeFolder is LocalFileHolder && model.sources.isNotEmpty()) {
                    Text(stringResource(R.string.save_here))
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                FilesTabContentView(tab)
            }
            if (model.busy) {
                // Keep the displayed destination stable while copying; Cancel remains available below.
                Box(Modifier.fillMaxSize().clickable(indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {})
            }
        }
    }
}
