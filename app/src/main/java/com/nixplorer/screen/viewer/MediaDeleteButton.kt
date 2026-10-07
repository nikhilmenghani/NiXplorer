package com.nixplorer.screen.viewer

import android.app.Activity
import android.net.Uri
import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nixplorer.R
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.ui.dialog.DeleteConfirmationDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaDeleteButton(uri: Uri) {
    val context = LocalContext.current
    val file by produceState<File?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            val candidate = when {
                uri.scheme == "file" -> uri.path?.let(::File)
                uri.authority == context.packageName + ".provider" -> {
                    var base: File? = null
                    context.resources.getXml(R.xml.provider_paths).use { paths ->
                        while (paths.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if (paths.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                                paths.getAttributeValue(null, "name") == uri.pathSegments.firstOrNull()) {
                                val root = when (paths.name) {
                                    "root-path" -> File("/")
                                    "external-path" -> Environment.getExternalStorageDirectory()
                                    "cache-path" -> context.cacheDir
                                    "files-path" -> context.filesDir
                                    else -> null
                                }
                                base = root?.let { File(it, paths.getAttributeValue(null, "path") ?: "").canonicalFile }
                                break
                            }
                        }
                    }
                    base?.let { root ->
                        File(root, uri.pathSegments.drop(1).joinToString("/")).canonicalFile.takeIf {
                            it.toPath().startsWith(root.toPath())
                        }
                    }
                }
                else -> null
            }
            candidate?.takeIf { it.isFile && it.parentFile?.canWrite() == true }
        }
    }
    var confirming by remember(uri) { mutableStateOf(false) }
    IconButton(enabled = file != null, onClick = { confirming = true }) {
        Icon(Icons.Rounded.Delete, stringResource(R.string.delete))
    }
    if (confirming) file?.let { target ->
        val tab = remember(target) {
            FilesTab(LocalFileHolder(target.parentFile!!)).apply {
                val holder = LocalFileHolder(target)
                selectedFiles[holder.uniquePath] = holder
            }
        }
        DeleteConfirmationDialog(true, tab,
            onConfirmed = { (context as? Activity)?.finish() },
            onDismissRequest = { confirming = false })
    }
}
