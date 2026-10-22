package com.nixplorer.screen.viewer.pdf.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.nixplorer.theme.FileExplorerTheme
import com.nixplorer.common.ui.FileOperationTheme
import com.nixplorer.common.ui.SafeSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PdfEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val editor = ViewModelProvider(this)[PdfEditorController::class.java]
        if (!editor.initialized) {
            editor.initialized = true
            intent.data?.let { editor.open(it) }
        }
        setContent {
            FileExplorerTheme { FileOperationTheme { SafeSurface { PdfEditorScreen(editor) { finish() } } } }
        }
    }
}

/** Keeps edits across activity recreation; source bytes and commands are retained until this editor closes. */
class PdfEditorController(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineMutex = Mutex()
    private val directory = File(context.cacheDir, "pdf-editor-${UUID.randomUUID()}")
    private var cancellation = AtomicBoolean(false)
    private var job: Job? = null
    private var savedOperations: List<PdfEditOperation> = emptyList()
    private var pendingOpenUri: Uri? = null
    private var pendingCachedUri: Uri? = null
    private var originalUri: Uri? = null
    var initialized = false
    var source by mutableStateOf<Uri?>(null)
        private set
    var name by mutableStateOf("document.pdf")
        private set
    var password by mutableStateOf<String?>(null)
        private set
    var passwordRequired by mutableStateOf(false)
        private set
    var info by mutableStateOf<PdfDocumentInfo?>(null)
        private set
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var page by mutableIntStateOf(0)
        private set
    var operations by mutableStateOf<List<PdfEditOperation>>(emptyList())
        private set
    var redoOperations by mutableStateOf<List<PdfEditOperation>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var busyLabel by mutableStateOf("Opening document")
        private set
    var cancelling by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(-1f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saved by mutableStateOf(false)
        private set
    var exported by mutableStateOf(false)
        private set
    val dirty: Boolean get() = operations != savedOperations
    val canUndo: Boolean get() = operations.isNotEmpty() && !busy
    val canRedo: Boolean get() = redoOperations.isNotEmpty() && !busy

    fun dismissError() { error = null }
    fun dismissSaved() { saved = false; exported = false }
    fun cancelPassword() { passwordRequired = false; pendingOpenUri = null; pendingCachedUri = null }
    fun cancel() { cancellation.set(true); cancelling = true }

    private fun perform(label: String, onFinished: (() -> Unit)? = null, action: suspend (AtomicBoolean) -> Unit) {
        if (busy) return
        busy = true
        busyLabel = label
        error = null
        progress = -1f
        cancelling = false
        val cancelled = AtomicBoolean(false)
        cancellation = cancelled
        job = scope.launch {
            var completed = false
            try { action(cancelled); completed = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!cancelled.get()) {
                    val passwordError = pendingOpenUri != null && (e.javaClass.simpleName.contains("InvalidPassword", true) ||
                        e.message.orEmpty().contains("password", true))
                    if (passwordError) passwordRequired = true
                    else error = e.message?.take(500) ?: "The PDF operation could not be completed."
                }
            } finally {
                busy = false
                cancelling = false
            }
            if (completed && !cancelled.get()) onFinished?.invoke()
        }
    }

    fun open(uri: Uri, enteredPassword: String? = null) {
        if (pendingOpenUri != uri) pendingCachedUri = null
        pendingOpenUri = uri
        perform("Opening document") { cancelled ->
            val imported = pendingCachedUri ?: withContext(Dispatchers.IO) {
                engineMutex.withLock { cacheUri(uri, ".pdf", cancelled) }
            }.also { pendingCachedUri = it }
            val document = withContext(Dispatchers.IO) {
                engineMutex.withLock { PdfEditorEngine.inspect(context, imported, enteredPassword, cancelled = { cancelled.get() }) }
            }
            val preview = withContext(Dispatchers.IO) {
                engineMutex.withLock { PdfEditorEngine.render(context, imported, 0, 1200, enteredPassword, cancelled = { cancelled.get() }) }
            }
            if (cancelled.get()) return@perform
            originalUri = uri
            source = imported
            name = withContext(Dispatchers.IO) { displayName(uri) }
            password = enteredPassword
            passwordRequired = false
            info = document
            bitmap = preview
            page = 0
            operations = emptyList()
            redoOperations = emptyList()
            savedOperations = emptyList()
            pendingOpenUri = null
            pendingCachedUri = null
        }
    }

    fun retryPassword(value: String) { pendingOpenUri?.let { open(it, value) } }

    fun unlock(ownerPassword: String) {
        if (source == null) return
        perform("Removing password protection") { cancelled ->
            val candidate = operations + PdfEditOperation.Unlock
            val result = withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    val document = PdfEditorEngine.inspect(context, source!!, ownerPassword, candidate, { cancelled.get() })
                    val index = page.coerceIn(0, document.pageCount - 1)
                    val preview = PdfEditorEngine.render(context, source!!, index, 1200, ownerPassword, candidate, { cancelled.get() })
                    document to preview
                }
            }
            if (!cancelled.get()) {
                password = ownerPassword; operations = candidate; redoOperations = emptyList()
                info = result.first; bitmap = result.second
            }
        }
    }

    private suspend fun renderCandidate(candidate: List<PdfEditOperation>, desiredPage: Int, cancelled: AtomicBoolean): Pair<PdfDocumentInfo, Bitmap> {
        val input = requireNotNull(source)
        return withContext(Dispatchers.IO) {
            engineMutex.withLock {
                val document = PdfEditorEngine.inspect(context, input, password, candidate, { cancelled.get() })
                val index = desiredPage.coerceIn(0, document.pageCount - 1)
                val image = PdfEditorEngine.render(context, input, index, 1200, password, candidate, { cancelled.get() })
                document to image
            }
        }
    }

    fun apply(operation: PdfEditOperation, desiredPage: Int = page) {
        if (source == null) return
        perform("Applying changes") { cancelled ->
            val candidate = operations + operation
            val (document, preview) = renderCandidate(candidate, desiredPage, cancelled)
            if (!cancelled.get()) {
                operations = candidate
                redoOperations = emptyList()
                info = document
                bitmap = preview
                page = desiredPage.coerceIn(0, document.pageCount - 1)
            }
        }
    }

    fun undo() {
        if (!canUndo) return
        perform("Undoing change") { cancelled ->
            val last = operations.last()
            val candidate = operations.dropLast(1)
            val (document, preview) = renderCandidate(candidate, page, cancelled)
            if (!cancelled.get()) {
                operations = candidate
                redoOperations = redoOperations + last
                info = document
                bitmap = preview
                page = page.coerceIn(0, document.pageCount - 1)
            }
        }
    }

    fun redo() {
        if (!canRedo) return
        perform("Redoing change") { cancelled ->
            val operation = redoOperations.last()
            val candidate = operations + operation
            val (document, preview) = renderCandidate(candidate, page, cancelled)
            if (!cancelled.get()) {
                operations = candidate
                redoOperations = redoOperations.dropLast(1)
                info = document
                bitmap = preview
                page = page.coerceIn(0, document.pageCount - 1)
            }
        }
    }

    fun selectPage(index: Int) {
        val count = info?.pageCount ?: return
        if (index !in 0 until count || index == page || source == null) return
        perform("Rendering page") { cancelled ->
            val preview = withContext(Dispatchers.IO) {
                engineMutex.withLock { PdfEditorEngine.render(context, source!!, index, 1200, password, operations, { cancelled.get() }) }
            }
            if (!cancelled.get()) { page = index; bitmap = preview }
        }
    }

    fun importFile(uri: Uri, suffix: String, onReady: (Uri) -> Unit) {
        var imported: Uri? = null
        perform("Reading selected file", onFinished = { imported?.let(onReady) }) { cancelled ->
            imported = withContext(Dispatchers.IO) { engineMutex.withLock { cacheUri(uri, suffix, cancelled) } }
        }
    }

    fun merge(uris: List<Uri>) {
        if (uris.isEmpty()) return
        perform("Merging documents") { cancelled ->
            val imported = withContext(Dispatchers.IO) { engineMutex.withLock { uris.map { cacheUri(it, ".pdf", cancelled) } } }
            val candidate = operations + PdfEditOperation.Merge(imported)
            val (document, preview) = renderCandidate(candidate, page, cancelled)
            if (!cancelled.get()) {
                operations = candidate; redoOperations = emptyList(); info = document; bitmap = preview
            }
        }
    }

    fun save(output: Uri) {
        if (source == null) return
        if (output == originalUri) { error = "Choose a new document to keep the original unchanged."; return }
        perform("Saving edited copy") { cancelled ->
            withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    PdfOutputSafety.requireDifferentFile(context, requireNotNull(originalUri), output)
                    PdfEditorEngine.export(context, source!!, output, operations, password, { cancelled.get() }) { fraction ->
                        scope.launch { progress = fraction }
                    }
                }
            }
            if (!cancelled.get()) { savedOperations = operations.toList(); saved = true }
        }
    }

    fun export(kind: PdfExportKind, output: Uri) {
        if (source == null) return
        perform("Exporting document") { cancelled ->
            withContext(Dispatchers.IO) {
                engineMutex.withLock {
                    PdfOutputSafety.requireDifferentFile(context, requireNotNull(originalUri), output)
                    PdfExportEngine.export(context, source!!, password, operations, kind, output, { cancelled.get() }) { current, total ->
                        scope.launch { progress = if (total > 0) current.toFloat() / total else -1f }
                    }
                }
            }
            if (!cancelled.get()) exported = true
        }
    }

    private fun cacheUri(uri: Uri, suffix: String, cancelled: AtomicBoolean): Uri {
        directory.mkdirs()
        val file = File.createTempFile("source-", suffix, directory)
        try {
            val input = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
                else context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read this file")
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (cancelled.get()) throw CancellationException("Cancelled")
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 64L * 1024 * 1024) throw IOException("This file exceeds the 64 MB editing limit")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("This file is empty")
            } }
            return Uri.fromFile(file)
        } catch (e: Exception) { file.delete(); throw e }
    }

    private fun displayName(uri: Uri): String = runCatching {
        if (uri.scheme == "file") File(uri.path.orEmpty()).name else {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "document.pdf"

    override fun onCleared() {
        cancellation.set(true)
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { engineMutex.withLock { directory.deleteRecursively() } }
    }
}
