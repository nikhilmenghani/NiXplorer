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
import androidx.compose.ui.focus.focusRequester
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
                var prepared by remember(source) { mutableStateOf<File?>(null) }
                var passwordPrompt by remember(source) { mutableStateOf(false) }
                var passwordText by remember(source) { mutableStateOf("") }
                var passwordError by remember(source) { mutableStateOf(false) }
                var credential by remember(source) { mutableStateOf<CharArray?>(null) }
                var attempt by remember(source) { mutableIntStateOf(0) }
                var opened by remember(source) { mutableStateOf(false) }
                DisposableEffect(source) {
                    onDispose {
                        credential?.fill('\u0000')
                        if (!opened) prepared?.let { file ->
                            file.delete()
                            File(file.parentFile, file.name + ".zip").delete()
                            File(file.parentFile, file.name + ".unlocked.zip").delete()
                            file.parentFile?.delete()
                        }
                    }
                }
                LaunchedEffect(source) {
                    try {
                        prepared = withContext(Dispatchers.IO) {
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
                                target
                            } catch (e: Exception) {
                                target.delete()
                                directory.delete()
                                throw e
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { globalClass.logger.logError(e); failed = true }
                }
                LaunchedEffect(prepared, attempt) {
                    val file = prepared ?: return@LaunchedEffect
                    val password = credential
                    try {
                        val archive = withContext(Dispatchers.IO) {
                            if (runCatching { ZipFile(file).use { } }.isSuccess) unlockZip(file, password)
                            else if (isSevenZip(file)) unlockSevenZip(file, password)
                            else if (isRar(file)) unlockRar(file, password)
                            else convertArchiveToZip(file, password)
                        }
                        globalClass.zipManager.openArchive(LocalFileHolder(archive), inNewTab = true)
                        opened = true
                        startActivity(Intent(this@ArchiveOpenActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    } catch (e: CancellationException) { throw e }
                    catch (_: ArchivePasswordRequired) {
                        passwordError = password != null
                        passwordPrompt = true
                    } catch (e: Exception) { globalClass.logger.logError(e); failed = true }
                    finally { password?.fill('\u0000'); credential = null }
                }
                fun submitPassword() {
                    if (passwordText.isEmpty()) return
                    credential = passwordText.toCharArray()
                    passwordText = ""
                    passwordPrompt = false
                    attempt++
                }
                if (passwordPrompt) com.nixplorer.common.ui.TextEntryTopSheet(stringResource(R.string.archive_password_title), { finish() }) { focus ->
                    Text(prepared?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = passwordText, onValueChange = { passwordText = it; passwordError = false },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        label = { Text(stringResource(R.string.archive_password)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = passwordError,
                        supportingText = if (passwordError) ({ Text(stringResource(R.string.archive_password_incorrect)) }) else null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submitPassword() }))
                    com.nixplorer.common.ui.SheetActionButton(onClick = ::submitPassword, enabled = passwordText.isNotEmpty()) { Text(stringResource(R.string.open)) }
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
