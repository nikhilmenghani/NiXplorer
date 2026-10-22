package com.nixplorer.screen.viewer.pdf.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nixplorer.R
import kotlinx.coroutines.launch
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PdfToolPalette(group: PdfToolGroup, select: (PdfToolGroup) -> Unit, enabled: Boolean, choose: (PdfTool) -> Unit, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdfToolGroup.entries.forEach { item -> FilterChip(group == item, onClick = { select(item) }, label = { Text(item.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            FlowRow(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PdfTool.entries.filter { it.group == group }.forEach { tool ->
                    FilledTonalButton(onClick = { choose(tool) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text(tool.label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
internal fun PdfEditingPage(editor: PdfEditorController, placement: PdfPlacement?, modifier: Modifier,
    place: (PdfPlacement, PdfRect, List<PdfPoint>) -> Unit) {
    val image = editor.bitmap
    var points by remember(placement, editor.page) { mutableStateOf<List<PdfPoint>>(emptyList()) }
    val zoom = rememberZoomState(maxScale = 5f)
    val scope = rememberCoroutineScope()
    var pageSize by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(editor.source, editor.page) { zoom.reset() }
    LaunchedEffect(image?.width, image?.height) {
        image?.let { zoom.setContentSize(Size(it.width.toFloat(), it.height.toFloat())) }
    }
    fun changeZoom(multiplier: Float) {
        scope.launch { zoom.changeScale(zoom.scale * multiplier, Offset(pageSize.width / 2, pageSize.height / 2)) }
    }
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest).clipToBounds().padding(12.dp), contentAlignment = Alignment.Center) {
        if (image == null) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.pdf_editor_choose_document), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val aspect = image.width.toFloat() / image.height
            val width = min(maxWidth.value, maxHeight.value * aspect)
            val height = width / aspect
            val pointerModifier = if (placement == null || editor.busy) Modifier else Modifier
                .pointerInput(placement, editor.page) {
                    fun normalized(offset: Offset) = PdfPoint((offset.x / size.width).coerceIn(0f, 1f), (offset.y / size.height).coerceIn(0f, 1f))
                    detectDragGestures(onDragStart = { points = listOf(normalized(it)) }, onDragCancel = { points = emptyList() },
                        onDrag = { change, _ -> change.consume(); if (points.size < 4096) points = points + normalized(change.position) },
                        onDragEnd = {
                            if (points.size > 1) {
                                val first = points.first(); val last = points.last()
                                val rect = PdfRect(min(first.x, last.x), min(first.y, last.y), max(first.x, last.x), max(first.y, last.y))
                                val ink = placement.tool in setOf(PdfTool.DRAW, PdfTool.SIGN)
                                val line = placement.tool in setOf(PdfTool.LINE, PdfTool.ARROW)
                                val valid = if (line) rect.right - rect.left > .005f || rect.bottom - rect.top > .005f
                                    else rect.right - rect.left > .005f && rect.bottom - rect.top > .005f
                                if (ink || valid) place(placement, rect, points)
                            }
                            points = emptyList()
                        })
                }.pointerInput(placement, editor.page) {
                    detectTapGestures { offset ->
                        if (placement.tool in setOf(PdfTool.DRAW, PdfTool.SIGN, PdfTool.CROP, PdfTool.REDACT)) return@detectTapGestures
                        val x = (offset.x / size.width).coerceIn(0f, .9f); val y = (offset.y / size.height).coerceIn(0f, .95f)
                        val defaultWidth = if (placement.tool == PdfTool.CHECKBOX) .04f else .35f
                        val defaultHeight = if (placement.tool == PdfTool.CHECKBOX) .04f else .12f
                        place(placement, PdfRect(x, y, min(1f, x + defaultWidth), min(1f, y + defaultHeight)), emptyList())
                    }
                }
            Box(Modifier.width(width.dp).height(height.dp)
                .onSizeChanged { pageSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .zoomable(zoom, zoomEnabled = placement == null && !editor.busy,
                    enableOneFingerZoom = placement == null, onDoubleTap = null)
                .background(Color.White).then(pointerModifier)
                .semantics {
                    contentDescription = "PDF page ${editor.page + 1}. ${placement?.tool?.label.orEmpty()}"
                    stateDescription = "Zoom ${(zoom.scale * 100).toInt()} percent"
                    customActions = listOf(
                        CustomAccessibilityAction("Zoom in") { changeZoom(1.5f); true },
                        CustomAccessibilityAction("Zoom out") { changeZoom(1f / 1.5f); true },
                        CustomAccessibilityAction("Fit page") { scope.launch { zoom.reset() }; true }
                    )
                }) {
                Image(image.asImageBitmap(), stringResource(R.string.pdf_editor_page_preview), Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                if (points.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
                    if (placement?.tool in setOf(PdfTool.DRAW, PdfTool.SIGN)) {
                        val path = Path(); points.forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x * size.width, point.y * size.height) else path.lineTo(point.x * size.width, point.y * size.height) }
                        drawPath(path, Color(placement?.color ?: 0xff202124), style = Stroke(width = 3.dp.toPx()))
                    } else {
                        val start = points.first(); val end = points.last()
                        drawRect(primary, Offset(min(start.x, end.x) * size.width, min(start.y, end.y) * size.height), Size(abs(start.x - end.x) * size.width, abs(start.y - end.y) * size.height), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Surface(modifier = Modifier.align(Alignment.TopEnd), shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { changeZoom(1f / 1.5f) }, enabled = !editor.busy && zoom.scale > 1.01f,
                        modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ZoomOut, stringResource(R.string.pdf_editor_zoom_out)) }
                    Text("${(zoom.scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { changeZoom(1.5f) }, enabled = !editor.busy && zoom.scale < 4.99f,
                        modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ZoomIn, stringResource(R.string.pdf_editor_zoom_in)) }
                    IconButton(onClick = { scope.launch { zoom.reset() } }, enabled = !editor.busy,
                        modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.FitScreen, stringResource(R.string.pdf_editor_fit_page)) }
                }
            }
        }
    }
}

@Composable
internal fun PdfOptionsDialog(options: PdfOptions, dismiss: () -> Unit, apply: (Map<String, String>, Map<String, Boolean>) -> Unit) {
    var values by remember(options) { mutableStateOf(options.fields.associate { it.key to it.value }) }
    var checks by remember(options) { mutableStateOf(options.checks.associate { it.key to it.value }) }
    var error by remember(options) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(28.dp), title = { Text(options.title) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            options.fields.forEach { field -> OutlinedTextField(values[field.key].orEmpty(), { values = values + (field.key to it); error = null }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (field.secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default) }
            options.checks.forEach { check -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { checks = checks + (check.key to (checks[check.key] != true)) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checks[check.key] == true, onCheckedChange = { checks = checks + (check.key to it) }); Text(check.label, Modifier.weight(1f))
            } }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = { try { apply(values, checks) } catch (e: Exception) { error = e.message ?: "Check the entered values." } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(options.applyLabel) } },
        dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PdfEditorProgress(editor: PdfEditorController) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(Modifier.size(40.dp)); Text(editor.busyLabel, style = MaterialTheme.typography.titleMedium) }
                if (editor.progress >= 0f) LinearWavyProgressIndicator(progress = { editor.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = editor::cancel, enabled = !editor.cancelling, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) { Text(if (editor.cancelling) "Cancelling…" else stringResource(R.string.pdf_editor_cancel_operation)) }
            }
        }
    }
}
