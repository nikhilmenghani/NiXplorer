package com.nixplorer.screen.storage

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nixplorer.R
import com.nixplorer.common.toFormattedSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun StorageCapacityCard(total: Long, free: Long, title: String) {
    val used = (total - free).coerceAtLeast(0)
    val fraction = (used.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(),
                    strokeWidth = 7.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(used.toFormattedSize(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.analysis_free_of_total, free.toFormattedSize(), total.toFormattedSize()),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun AnalysisNotice(text: String, icon: ImageVector = Icons.Rounded.Info, content: @Composable ColumnScope.() -> Unit = {}) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        }
    }
}

internal fun storageFileIcon(file: File, folder: Boolean): ImageVector = when {
    folder -> Icons.Rounded.Folder
    file.extension.lowercase() in listOf("mp4", "mkv", "webm", "ts", "avi", "mov") -> Icons.Rounded.Movie
    file.extension.lowercase() in listOf("mp3", "flac", "wav", "m4a", "aac") -> Icons.Rounded.MusicNote
    file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "heic", "gif") -> Icons.Rounded.Image
    file.extension.lowercase() in listOf("zip", "7z", "rar", "apk", "apks", "gz") -> Icons.Rounded.Inventory2
    else -> Icons.Rounded.Description
}

@Composable
internal fun AppStorageIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(48, 48) }.getOrNull()
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp)) }
        ?: Icon(Icons.Rounded.Android, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun StorageUsageRow(
    title: String,
    subtitle: String,
    size: String,
    fraction: Float,
    icon: @Composable () -> Unit,
    openLabel: String,
    onOpen: () -> Unit,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
    detail: String? = null
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.analysis_item_actions, title))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(openLabel) }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) },
                            onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.analysis_details)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                            onClick = { menu = false; expanded = !expanded })
                        if (onRemove != null && removeLabel != null) DropdownMenuItem(
                            text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onRemove() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                Text(size, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
