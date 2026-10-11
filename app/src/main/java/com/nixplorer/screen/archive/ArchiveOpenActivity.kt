package com.nixplorer.screen.archive

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.base.BaseActivity
import com.nixplorer.screen.main.MainActivity
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.theme.FileExplorerTheme
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Materialize temporary URI grants before returning to the file browser. Never modify the source. */
class ArchiveOpenActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
    }

    override fun onPermissionGranted() {
        val source = intent.data
        if (source == null || source.scheme !in setOf("content", "file")) { finish(); return }
        setContent {
            FileExplorerTheme {
                var failed by remember(source) { mutableStateOf(false) }
                LaunchedEffect(source) {
                    try {
                        val archive = withContext(Dispatchers.IO) {
                            var name: String? = null
                            if (source.scheme == "content") {
                                contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                    if (it.moveToFirst()) name = it.getString(0)
                                }
                            } else name = source.lastPathSegment
                            val safeName = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
                                .replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").take(160)
                                .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "archive.zip"
                            val directory = File(cacheDir, "incoming-archives/${UUID.randomUUID()}").apply { check(mkdirs()) }
                            val target = File(directory, safeName)
                            try {
                                val input = if (source.scheme == "file") File(requireNotNull(source.path)).inputStream()
                                    else requireNotNull(contentResolver.openInputStream(source))
                                input.use { from -> target.outputStream().use { to ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = from.read(buffer)
                                        if (count < 0) break
                                        to.write(buffer, 0, count)
                                    }
                                } }
                                if (runCatching { ZipFile(target).use { } }.isSuccess) target
                                else convertArchiveToZip(target).also { target.delete() }
                            } catch (e: Exception) {
                                target.delete()
                                directory.delete()
                                throw e
                            }
                        }
                        globalClass.zipManager.openArchive(LocalFileHolder(archive), inNewTab = true)
                        startActivity(Intent(this@ArchiveOpenActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { globalClass.logger.logError(e); failed = true }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (failed) {
                            Text(stringResource(R.string.archive_open_failed))
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.dismiss)) }
                        } else {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.archive_opening))
                        }
                    }
                }
            }
        }
    }
}
