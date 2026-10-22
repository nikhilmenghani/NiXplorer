package com.nixplorer.screen.main.tab.files.ui.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.toFormattedSize
import com.nixplorer.common.ui.BottomSheetDialog
import com.nixplorer.common.ui.FileOperationTheme
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.conversion.*
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import kotlin.math.roundToInt

/** Uses the browser palette with Expressive controls and a footer that stays accessible while scrolling. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileConversionDialog(show: Boolean, tab: FilesTab, onDismissRequest: () -> Unit) {
    if (!show) return
    val sources = tab.conversionSources
    if (sources.isEmpty()) return
    val sourcePaths = remember(sources) { sources.map { it.uniquePath } }
    var selectedPaths by rememberSaveable(sourcePaths) { mutableStateOf(sourcePaths) }
    val selectedPathSet = remember(selectedPaths) { selectedPaths.toHashSet() }
    val selectedSources = remember(sources, selectedPathSet) { sources.filter { it.uniquePath in selectedPathSet } }
    val formats = remember(selectedSources) { ConversionCatalog.commonFormats(selectedSources.map { it.file }) }
    var selectedFormatName by rememberSaveable(sourcePaths) { mutableStateOf<String?>(null) }
    val selectedFormat = formats.firstOrNull { it.name == selectedFormatName } ?: formats.firstOrNull()
    LaunchedEffect(selectedFormat) { selectedFormatName = selectedFormat?.name }
    var quality by rememberSaveable(sourcePaths) { mutableStateOf(90f) }
    var reviewFiles by rememberSaveable(sourcePaths) { mutableStateOf(false) }
    var showDetails by rememberSaveable(sourcePaths) { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val destination = (tab.activeFolder as? LocalFileHolder)?.file
    val destinationValid = destination != null && destination.isDirectory && destination.canWrite()
    val canQueue = !starting && selectedSources.isNotEmpty() && selectedFormat != null

    fun convertHere() {
        if (starting || selectedSources.isEmpty() || selectedFormat == null) return
        val folder = (tab.activeFolder as? LocalFileHolder)?.file
        if (folder == null || !folder.isDirectory || !folder.canWrite()) {
            globalClass.showMsg(globalClass.getString(R.string.conversion_navigate_destination))
            return
        }
        starting = true
        globalClass.taskManager.addTaskAndRun(
            ConversionTask(selectedSources.toList(), selectedFormat!!, quality.roundToInt()),
            ConversionTaskParameters(folder.absolutePath)
        )
        tab.unselectAllFiles()
        onDismissRequest()
    }

    fun addTask() {
        if (starting || selectedSources.isEmpty() || selectedFormat == null) return
        starting = true
        globalClass.taskManager.addTask(ConversionTask(selectedSources.toList(), selectedFormat!!, quality.roundToInt()))
        tab.unselectAllFiles()
        onDismissRequest()
    }

    FileOperationTheme {
        BottomSheetDialog(onDismissRequest = onDismissRequest) {
            val configuration = LocalConfiguration.current
            val scrollWholeSheet = configuration.screenHeightDp < 480 || configuration.fontScale >= 1.6f
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .heightIn(min = if (reviewFiles && !scrollWholeSheet) (configuration.screenHeightDp * .84f).dp else 0.dp,
                        max = (configuration.screenHeightDp * .84f).dp)
                    .then(if (scrollWholeSheet) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConversionIcon(Icons.Rounded.Transform, prominent = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.conversion_title), style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.conversion_originals_kept), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismissRequest, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.close))
                        }
                    }

                    Column(Modifier.then(if (scrollWholeSheet) Modifier else
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                        .padding(horizontal = 20.dp).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ConversionSection {
                            Row(Modifier.fillMaxWidth()
                                .clickable(enabled = sources.size > 1) { reviewFiles = !reviewFiles }
                                .padding(16.dp).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if (sources.size > 1) Icons.Rounded.FileCopy else Icons.Rounded.InsertDriveFile,
                                    null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (sources.size == 1) sources.first().displayName
                                        else if (selectedSources.size == 1) stringResource(R.string.conversion_single_source)
                                        else stringResource(R.string.conversion_source_count, selectedSources.size),
                                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (sources.size == 1) sources.first().size.toFormattedSize()
                                        else stringResource(if (reviewFiles) R.string.conversion_hide_selection else R.string.conversion_review_selection),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (sources.size > 1) Icon(
                                    if (reviewFiles) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    stringResource(if (reviewFiles) R.string.conversion_hide_selection else R.string.conversion_review_selection))
                            }
                            AnimatedVisibility(reviewFiles) {
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 184.dp),
                                    contentPadding = PaddingValues(start = 8.dp, end = 12.dp, bottom = 8.dp)) {
                                    items(sources, key = { it.uniquePath }) { source ->
                                        val checked = source.uniquePath in selectedPathSet
                                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                            .toggleable(checked, role = Role.Checkbox, onValueChange = {
                                                selectedPaths = if (it) selectedPaths + source.uniquePath else selectedPaths - source.uniquePath
                                            }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked, onCheckedChange = null)
                                            Column(Modifier.weight(1f)) {
                                                Text(source.displayName, style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                val outputs = remember(source.uniquePath) {
                                                    ConversionCatalog.formatsFor(source.file).joinToString { shortFormat(it) }
                                                }
                                                Text(if (outputs.isBlank()) stringResource(R.string.conversion_no_formats)
                                                    else stringResource(R.string.conversion_available_formats, outputs),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val options: @Composable (Modifier) -> Unit = { modifier ->
                                ConversionOutputOptions(formats, selectedFormat, selectedSources.size, { selectedFormatName = it.name },
                                    quality, { quality = it }, modifier)
                            }
                            val notice: @Composable () -> Unit = {
                                selectedFormat?.let { ConversionNotice(it, showDetails, { showDetails = !showDetails }) }
                            }
                            if (maxWidth >= 500.dp) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    options(Modifier.weight(1f))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ConversionDestination(destination, destinationValid)
                                        notice()
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    options(Modifier.fillMaxWidth())
                                    notice()
                                    ConversionDestination(destination, destinationValid)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    ConversionActions(canQueue && destinationValid, canQueue, ::convertHere, ::addTask)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversionActions(canConvert: Boolean, canQueue: Boolean, onConvert: () -> Unit, onQueue: () -> Unit) {
    val configuration = LocalConfiguration.current
    val stackActions = minOf(configuration.screenWidthDp, 560) - 40 < 320 || configuration.fontScale > 1.3f
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (stackActions) {
            ConversionActionButton(false, canConvert, onConvert, Modifier.fillMaxWidth())
            ConversionActionButton(true, canQueue, onQueue, Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConversionActionButton(false, canConvert, onConvert, Modifier.weight(1f))
                ConversionActionButton(true, canQueue, onQueue, Modifier.weight(1f))
            }
        }
        Text(stringResource(R.string.conversion_queue_caption), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversionActionButton(queue: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val buttonModifier = modifier.heightIn(min = 56.dp).semantics { if (!enabled) disabled() }
    val content: @Composable RowScope.() -> Unit = {
        Icon(if (queue) Icons.Rounded.AddTask else Icons.AutoMirrored.Rounded.ArrowForward,
            null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (queue) R.string.conversion_add_task else R.string.conversion_convert_here),
            textAlign = TextAlign.Center)
    }
    if (queue) {
        FilledTonalButton(onClick, shapes = ButtonDefaults.shapes(), enabled = enabled, modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp), content = content)
    } else {
        Button(onClick, shapes = ButtonDefaults.shapes(), enabled = enabled, modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp), content = content)
    }
}

@Composable
private fun ConversionSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun ConversionIcon(icon: ImageVector, prominent: Boolean = false) {
    Surface(shape = if (prominent) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium,
        color = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(Modifier.size(if (prominent) 48.dp else 40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp),
                tint = if (prominent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversionOutputOptions(
    formats: List<ConversionFormat>, selected: ConversionFormat?, selectedCount: Int, onSelect: (ConversionFormat) -> Unit,
    quality: Float, onQualityChange: (Float) -> Unit, modifier: Modifier = Modifier
) {
    ConversionSection(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.conversion_output_format), style = MaterialTheme.typography.titleSmall)
            if (formats.isEmpty()) {
                Text(stringResource(when (selectedCount) {
                    0 -> R.string.conversion_select_files
                    1 -> R.string.conversion_unsupported
                    else -> R.string.conversion_no_common_format
                }),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else {
                FlowRow(Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    formats.forEach { format ->
                        val label = shortFormat(format)
                        val isSelected = selected == format
                        TonalToggleButton(checked = isSelected,
                            onCheckedChange = { if (it) onSelect(format) },
                            modifier = Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
                                role = Role.RadioButton
                                this.selected = isSelected
                                contentDescription = label
                                onClick { onSelect(format); true }
                            }) {
                            if (isSelected) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            if (selected == ConversionFormat.JPEG || selected == ConversionFormat.WEBP) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.conversion_quality_label), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("${quality.roundToInt()}%", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Slider(quality, onValueChange = onQualityChange, valueRange = 10f..100f, steps = 8,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = globalClass.getString(R.string.conversion_quality_label)
                    })
            }
        }
    }
}

@Composable
private fun ConversionDestination(destination: java.io.File?, valid: Boolean) {
    ConversionSection {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ConversionIcon(Icons.Rounded.FolderOpen)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.conversion_destination), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (valid) destination!!.name.ifBlank { destination.absolutePath }
                    else stringResource(R.string.conversion_choose_later), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (valid) destination!!.absolutePath else stringResource(R.string.conversion_navigate_destination),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConversionNotice(format: ConversionFormat, expanded: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .4f)) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(when (format) {
                    ConversionFormat.JPEG -> R.string.conversion_jpeg_summary
                    ConversionFormat.PNG -> R.string.conversion_png_summary
                    ConversionFormat.WEBP -> R.string.conversion_webp_summary
                    ConversionFormat.PDF, ConversionFormat.TXT, ConversionFormat.HTML, ConversionFormat.DOCX ->
                        R.string.conversion_document_summary
                    else -> R.string.conversion_media_summary
                }), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    stringResource(R.string.conversion_details), Modifier.size(20.dp))
            }
            AnimatedVisibility(expanded) {
                Text(ConversionCatalog.notice(format).orEmpty(),
                    Modifier.padding(start = 48.dp, end = 16.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun shortFormat(format: ConversionFormat): String = when (format) {
    ConversionFormat.JPEG -> "JPEG"
    ConversionFormat.WEBP -> "WebP"
    else -> format.extension.uppercase(java.util.Locale.ROOT)
}
