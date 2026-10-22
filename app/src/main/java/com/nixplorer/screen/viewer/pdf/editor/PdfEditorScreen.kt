package com.nixplorer.screen.viewer.pdf.editor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nixplorer.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PdfEditorScreen(editor: PdfEditorController, close: () -> Unit) {
    val context = LocalContext.current
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var options by remember { mutableStateOf<PdfOptions?>(null) }
    var placement by remember { mutableStateOf<PdfPlacement?>(null) }
    var group by rememberSaveable { mutableStateOf(PdfToolGroup.EDIT) }
    var exportKind by rememberSaveable { mutableStateOf(PdfExportKind.TEXT) }
    var importSignature by rememberSaveable { mutableStateOf(false) }
    var passwordText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { editor.open(it) } }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let { editor.save(it) } }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data?.let { editor.export(exportKind, it) }
    }
    val mergePdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { editor.merge(it) }
    val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { editor.importFile(it, ".image") { imported -> placement = PdfPlacement(if (importSignature) PdfTool.SIGN_IMAGE else PdfTool.IMAGE, image = imported) } }
    }
    fun requestExit(action: () -> Unit) { if (editor.dirty) pendingExit = action else action() }
    BackHandler { if (!editor.busy) requestExit(close) }
    val toolActions = PdfToolActions(context, editor, { options = it }, { placement = it }, { message = it },
        image = { signature -> importSignature = signature; importImage.launch(arrayOf("image/*")) },
        merge = { mergePdf.launch(arrayOf("application/pdf")) },
        export = { kind ->
            exportKind = kind
            val text = kind == PdfExportKind.TEXT
            val base = editor.name.substringBeforeLast('.', editor.name)
            exportFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (text) "text/plain" else "application/zip")
                .putExtra(Intent.EXTRA_TITLE, "$base-${kind.name.lowercase()}.${if (text) "txt" else "zip"}"))
        })

    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { requestExit(close) }, enabled = !editor.busy, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.pdf_editor_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(editor.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { requestExit { openPdf.launch(arrayOf("application/pdf")) } }, enabled = !editor.busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.FolderOpen, stringResource(R.string.pdf_editor_open)) }
                    FilledTonalButton(onClick = { placement = null; savePdf.launch(editor.name.substringBeforeLast('.', editor.name) + "-edited.pdf") }, enabled = editor.source != null && !editor.busy,
                        modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Rounded.SaveAs, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp)); Text("Save copy")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { placement = null; editor.undo() }, enabled = editor.canUndo, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.pdf_editor_undo)) }
                    IconButton(onClick = { placement = null; editor.redo() }, enabled = editor.canRedo, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.pdf_editor_redo)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { placement = null; editor.selectPage(editor.page - 1) }, enabled = !editor.busy && editor.page > 0, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, stringResource(R.string.pdf_editor_previous)) }
                    TextButton(onClick = {
                        options = PdfOptions("Go to page", listOf(PdfOptionField("page", "Page number", "${editor.page + 1}"))) { v, _ ->
                            placement = null; editor.selectPage(pdfNumber(v, "page", 1f, (editor.info?.pageCount ?: 1).toFloat()).toInt() - 1)
                        }
                    }, enabled = editor.info != null && !editor.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (editor.info != null) stringResource(R.string.pdf_editor_page, editor.page + 1, editor.info!!.pageCount) else "No document", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick = { placement = null; editor.selectPage(editor.page + 1) }, enabled = !editor.busy && editor.page + 1 < (editor.info?.pageCount ?: 0), modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, stringResource(R.string.pdf_editor_next)) }
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (editor.info?.signed == true) Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(stringResource(R.string.pdf_editor_signed_warning), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            if (editor.info?.canModify == false) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("Editing is restricted. Use Security → Remove password with the owner password before editing.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            placement?.let { active -> Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(active.tool.label, style = MaterialTheme.typography.labelLarge)
                        Text(if (active.tool == PdfTool.CROP || active.tool == PdfTool.REDACT) "Drag to select an area" else stringResource(R.string.pdf_editor_tap_to_place), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { placement = null }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Finish placing") }
                }
            } }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp || (maxWidth >= 520.dp && maxWidth > maxHeight * 1.4f)
                val palette: @Composable (Modifier) -> Unit = { modifier -> PdfToolPalette(group, { group = it }, !editor.busy && editor.info != null, { placement = null; toolActions.choose(it) }, modifier) }
                val preview: @Composable (Modifier) -> Unit = { modifier -> PdfEditingPage(editor, placement, modifier) { selected, rect, points ->
                    editor.apply(pdfPlacementOperation(selected, editor.page, rect, points))
                    if (selected.tool !in setOf(PdfTool.DRAW, PdfTool.SIGN)) placement = null
                } }
                if (wide) Row(Modifier.fillMaxSize()) { preview(Modifier.weight(1f).fillMaxHeight()); palette(Modifier.width(300.dp).fillMaxHeight()) }
                else Column(Modifier.fillMaxSize()) { preview(Modifier.weight(1f).fillMaxWidth()); palette(Modifier.fillMaxWidth().heightIn(max = 228.dp)) }
            }
        }
    }
    options?.let { chosen -> PdfOptionsDialog(chosen, { options = null }) { values, checks -> chosen.apply(values, checks); options = null } }
    pendingExit?.let { action -> AlertDialog(onDismissRequest = { pendingExit = null }, title = { Text(stringResource(R.string.pdf_editor_discard_title)) },
        text = { Text(stringResource(R.string.pdf_editor_discard_message)) }, confirmButton = { TextButton(onClick = { pendingExit = null; action() }) { Text(stringResource(R.string.pdf_editor_discard)) } },
        dismissButton = { TextButton(onClick = { pendingExit = null }) { Text(stringResource(R.string.pdf_editor_keep_editing)) } }) }
    if (editor.passwordRequired) AlertDialog(onDismissRequest = editor::cancelPassword,
        title = { Text(stringResource(R.string.pdf_editor_password_title)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pdf_editor_password_note))
            OutlinedTextField(passwordText, { passwordText = it }, label = { Text(stringResource(R.string.pdf_editor_password_hint)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        } }, confirmButton = { TextButton(onClick = { editor.retryPassword(passwordText); passwordText = "" }) { Text(stringResource(R.string.pdf_editor_unlock)) } },
        dismissButton = { TextButton(onClick = editor::cancelPassword) { Text(stringResource(R.string.cancel)) } })
    (editor.error ?: message)?.let { value -> AlertDialog(onDismissRequest = { editor.dismissError(); message = null }, title = { Text(stringResource(R.string.pdf_editor_error)) },
        text = { Text(value) }, confirmButton = { TextButton(onClick = { editor.dismissError(); message = null }) { Text(stringResource(R.string.close)) } }) }
    if (editor.saved || editor.exported) AlertDialog(onDismissRequest = editor::dismissSaved, icon = { Icon(Icons.Rounded.CheckCircle, null) }, title = { Text(if (editor.saved) stringResource(R.string.pdf_editor_saved) else "Export saved") },
        text = { Text(stringResource(R.string.pdf_editor_original_kept)) }, confirmButton = { TextButton(onClick = editor::dismissSaved) { Text(stringResource(R.string.close)) } })
    if (editor.busy) PdfEditorProgress(editor)
}
