package com.nixplorer.screen.viewer.pdf.editor

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import com.nixplorer.R

internal enum class PdfToolGroup(val label: String) { EDIT("Edit"), PAGES("Pages"), DOCUMENT("Document"), SECURITY("Security"), EXPORT("Export") }
internal enum class PdfTool(val label: String, val group: PdfToolGroup) {
    TEXT("Add text", PdfToolGroup.EDIT), IMAGE("Add image", PdfToolGroup.EDIT), DRAW("Draw", PdfToolGroup.EDIT), SIGN("Draw signature", PdfToolGroup.EDIT), SIGN_IMAGE("Signature image", PdfToolGroup.EDIT),
    RECTANGLE("Rectangle", PdfToolGroup.EDIT), ELLIPSE("Ellipse", PdfToolGroup.EDIT), LINE("Line", PdfToolGroup.EDIT), ARROW("Arrow", PdfToolGroup.EDIT), HIGHLIGHT("Highlight", PdfToolGroup.EDIT), UNDERLINE("Underline", PdfToolGroup.EDIT), STRIKEOUT("Strikeout", PdfToolGroup.EDIT), WHITEOUT("Whiteout", PdfToolGroup.EDIT), REDACT("Remove region", PdfToolGroup.EDIT), LINK("Add link", PdfToolGroup.EDIT), FILL_FORM("Fill form", PdfToolGroup.EDIT), TEXT_FIELD("Text field", PdfToolGroup.EDIT), CHECKBOX("Checkbox", PdfToolGroup.EDIT),
    ORDER("Reorder / extract pages", PdfToolGroup.PAGES), ROTATE("Rotate", PdfToolGroup.PAGES), DELETE("Delete page", PdfToolGroup.PAGES), DUPLICATE("Duplicate page", PdfToolGroup.PAGES), BLANK("Insert blank page", PdfToolGroup.PAGES), MERGE("Merge PDFs", PdfToolGroup.PAGES), CROP("Crop page", PdfToolGroup.PAGES), RESIZE("Resize pages", PdfToolGroup.PAGES), N_UP("Multiple pages per sheet", PdfToolGroup.PAGES), FLIP("Mirror pages", PdfToolGroup.PAGES),
    WATERMARK("Watermark", PdfToolGroup.DOCUMENT), NUMBERS("Page numbers / Bates", PdfToolGroup.DOCUMENT), HEADER("Header / footer", PdfToolGroup.DOCUMENT), METADATA("Document properties", PdfToolGroup.DOCUMENT), BOOKMARK("Add bookmark", PdfToolGroup.DOCUMENT), REMOVE_BOOKMARKS("Remove bookmarks", PdfToolGroup.DOCUMENT), FLATTEN("Flatten forms", PdfToolGroup.DOCUMENT), REMOVE_ANNOTATIONS("Remove annotations", PdfToolGroup.DOCUMENT), COMPRESS("Rasterize / compress", PdfToolGroup.DOCUMENT), GRAYSCALE("Grayscale copy", PdfToolGroup.DOCUMENT),
    PROTECT("Password protect", PdfToolGroup.SECURITY), UNLOCK("Remove password", PdfToolGroup.SECURITY), PNG("Pages to PNG ZIP", PdfToolGroup.EXPORT), JPEG("Pages to JPEG ZIP", PdfToolGroup.EXPORT), IMAGES("Extract images ZIP", PdfToolGroup.EXPORT), TEXT_EXPORT("Extract text", PdfToolGroup.EXPORT), SPLIT("Split pages ZIP", PdfToolGroup.EXPORT), SPLIT_BOOKMARKS("Split by bookmarks ZIP", PdfToolGroup.EXPORT), SPLIT_HALVES("Split page halves ZIP", PdfToolGroup.EXPORT)
}
internal data class PdfPlacement(val tool: PdfTool, val text: String = "", val size: Float = 16f, val color: Long = 0xff202124, val width: Float = 2f, val fill: Boolean = false, val bold: Boolean = false, val image: Uri? = null, val checked: Boolean = false)
internal data class PdfOptionField(val key: String, val label: String, val value: String = "", val secret: Boolean = false)
internal data class PdfOptionCheck(val key: String, val label: String, val value: Boolean = false)
internal data class PdfOptions(val title: String, val fields: List<PdfOptionField> = emptyList(), val checks: List<PdfOptionCheck> = emptyList(), val note: String? = null, val applyLabel: String = "Apply", val apply: (Map<String, String>, Map<String, Boolean>) -> Unit)
internal fun pdfNumber(values: Map<String, String>, key: String, low: Float, high: Float): Float = (values[key]?.toFloatOrNull() ?: error("Enter a valid number")).also { require(it in low..high) { "Value must be between $low and $high" } }
private fun pdfColor(values: Map<String, String>): Long = AndroidColor.parseColor(values["color"].orEmpty()).toLong() and 0xffffffffL

internal class PdfToolActions(private val context: Context, private val editor: PdfEditorController,
    private val options: (PdfOptions) -> Unit, private val place: (PdfPlacement) -> Unit,
    private val message: (String) -> Unit, private val image: (Boolean) -> Unit,
    private val merge: () -> Unit, private val export: (PdfExportKind) -> Unit) {
    private val flattenNote = "This operation flattens form values and annotation appearances. Interactive fields and links are lost; bookmarks may be removed. Review the edited copy."
    private fun fields(vararg values: PdfOptionField) = values.toList()
    fun choose(tool: PdfTool) {
        if (editor.busy || editor.info == null) return
        val page = editor.page
        val count = editor.info!!.pageCount
        val drawingFields = fields(PdfOptionField("color", "Color (#RRGGBB)", "#202124"), PdfOptionField("width", "Line width (points)", "2"))
        when (tool) {
            PdfTool.IMAGE -> image(false)
            PdfTool.SIGN_IMAGE -> options(PdfOptions(tool.label, note = context.getString(R.string.pdf_editor_drawing_signature_note), applyLabel = "Choose image") { _, _ -> image(true) })
            PdfTool.TEXT -> options(PdfOptions(tool.label, fields(PdfOptionField("text", "Text"), PdfOptionField("size", "Font size (points)", "16"), PdfOptionField("color", "Color (#RRGGBB)", "#202124")), listOf(PdfOptionCheck("bold", "Bold")), context.getString(R.string.pdf_editor_text_note), "Place on page") { v, c ->
                require(!v["text"].isNullOrBlank()) { "Enter text" }; place(PdfPlacement(tool, v["text"].orEmpty(), pdfNumber(v, "size", 4f, 200f), pdfColor(v), bold = c["bold"] == true))
            })
            PdfTool.DRAW, PdfTool.SIGN -> options(PdfOptions(tool.label, drawingFields,
                note = if (tool == PdfTool.SIGN) context.getString(R.string.pdf_editor_drawing_signature_note) else "Drag on the page to draw. Finish drawing before saving.", applyLabel = "Draw on page") { v, _ -> place(PdfPlacement(tool, color = pdfColor(v), width = pdfNumber(v, "width", .2f, 40f))) })
            PdfTool.RECTANGLE, PdfTool.ELLIPSE, PdfTool.LINE, PdfTool.ARROW -> options(PdfOptions(tool.label, drawingFields, listOf(PdfOptionCheck("fill", "Filled shape")), applyLabel = "Place on page") { v, c -> place(PdfPlacement(tool, color = pdfColor(v), width = pdfNumber(v, "width", .2f, 40f), fill = c["fill"] == true)) })
            PdfTool.HIGHLIGHT, PdfTool.UNDERLINE, PdfTool.STRIKEOUT -> options(PdfOptions(tool.label, fields(PdfOptionField("color", "Color (#RRGGBB)", if (tool == PdfTool.HIGHLIGHT) "#FFE082" else "#D32F2F")), note = "Drag across the area to mark. This adds a positioned annotation.", applyLabel = "Place on page") { v, _ -> place(PdfPlacement(tool, color = pdfColor(v))) })
            PdfTool.WHITEOUT -> options(PdfOptions(tool.label, note = context.getString(R.string.pdf_editor_visual_cover_warning), applyLabel = "Select area") { _, _ -> place(PdfPlacement(tool)) })
            PdfTool.REDACT -> options(PdfOptions(tool.label, fields(PdfOptionField("text", "Optional replacement text"), PdfOptionField("size", "Font size", "16")), note = "Rasterizes the affected page at 144 dpi and removes the selected region. Searchable text and links on that page are lost. Forms across the entire document are flattened; bookmarks, metadata and named links are removed. Review the saved copy carefully.", applyLabel = "Select area") { v, _ -> place(PdfPlacement(tool, v["text"].orEmpty(), pdfNumber(v, "size", 4f, 100f))) })
            PdfTool.LINK -> options(PdfOptions(tool.label, fields(PdfOptionField("url", "Web address", "https://")), applyLabel = "Place link area") { v, _ ->
                val url = v["url"].orEmpty().trim(); require(Uri.parse(url).scheme?.lowercase() in setOf("https", "http", "mailto", "tel")) { "Use an http, https, mailto or tel address" }; place(PdfPlacement(tool, text = url))
            })
            PdfTool.TEXT_FIELD, PdfTool.CHECKBOX -> options(PdfOptions(tool.label, fields(PdfOptionField("name", "Unique field name")), listOf(PdfOptionCheck("check", if (tool == PdfTool.TEXT_FIELD) "Multiline text" else "Initially checked")), applyLabel = "Place on page") { v, c ->
                val name = v["name"].orEmpty().trim(); require(name.isNotBlank() && editor.info!!.fields.none { it.name == name }) { "Enter a unique field name" }; place(PdfPlacement(tool, text = name, checked = c["check"] == true))
            })
            PdfTool.FILL_FORM -> {
                val editable = editor.info!!.fields.filterNot { it.readOnly || it.type.equals("Sig", true) }
                if (editable.isEmpty()) message("No editable fields found. Add a text field or checkbox first.")
                else options(PdfOptions(tool.label, editable.map { PdfOptionField(it.name, "${it.name} (${it.type})", it.value) }, note = "For checkbox values, use true or false. Read-only fields are excluded.") { v, _ -> editor.apply(PdfEditOperation.FillForm(v)) })
            }
            PdfTool.ORDER -> options(PdfOptions(tool.label, fields(PdfOptionField("pages", "Page order / ranges", (1..count).joinToString(","))), note = "Use 1,3,2 or ranges such as 1-3,5; all, odd and even are supported. Omit pages to remove them; repeat a number to duplicate it. At least one page is required. $flattenNote") { v, _ -> editor.apply(PdfEditOperation.PageOrder(PdfPageSelection.parse(v["pages"].orEmpty(), count)), 0) })
            PdfTool.ROTATE -> options(PdfOptions(tool.label, fields(PdfOptionField("angle", "Clockwise degrees (90, 180, 270)", "90")), listOf(PdfOptionCheck("all", "All pages"))) { v, c ->
                val angle = v["angle"]?.toIntOrNull(); require(angle in listOf(90, 180, 270)) { "Choose 90, 180 or 270 degrees" }; editor.apply(PdfEditOperation.Rotate(if (c["all"] == true) (0 until count).toList() else listOf(page), angle!!))
            })
            PdfTool.DELETE -> if (count <= 1) message("Keep at least one page in the document.") else options(PdfOptions(tool.label, note = "Remove page ${page + 1} from the edited copy? $flattenNote") { _, _ -> editor.apply(PdfEditOperation.PageOrder((0 until count).filter { it != page }), maxOf(0, page - 1)) })
            PdfTool.DUPLICATE -> options(PdfOptions(tool.label, note = "Duplicate page ${page + 1}. $flattenNote") { _, _ -> editor.apply(PdfEditOperation.PageOrder((0 until count).flatMap { if (it == page) listOf(it, it) else listOf(it) }), page + 1) })
            PdfTool.BLANK -> editor.apply(PdfEditOperation.BlankPage(page), page + 1)
            PdfTool.MERGE -> options(PdfOptions(tool.label, note = "Select PDFs to append to this document. Password-protected inputs must be unlocked first. $flattenNote", applyLabel = "Choose PDFs") { _, _ -> merge() })
            PdfTool.CROP -> options(PdfOptions(tool.label, note = "Changes the visible bounds of this page. Content outside the crop can remain in the file. This is not secure content removal.", applyLabel = "Select crop area") { _, _ -> place(PdfPlacement(tool)) })
            PdfTool.RESIZE -> options(PdfOptions(tool.label, fields(PdfOptionField("width", "Page width (points)", "595"), PdfOptionField("height", "Page height (points)", "842")), note = "Resizes all pages; 72 points equal one inch. $flattenNote") { v, _ -> editor.apply(PdfEditOperation.Resize(pdfNumber(v, "width", 72f, 3000f), pdfNumber(v, "height", 72f, 3000f))) })
            PdfTool.N_UP -> options(PdfOptions(tool.label, fields(PdfOptionField("columns", "Columns", "2"), PdfOptionField("rows", "Rows", "1")), note = "Combines pages onto sheets. $flattenNote") { v, _ -> editor.apply(PdfEditOperation.NUp(pdfNumber(v, "columns", 1f, 4f).toInt(), pdfNumber(v, "rows", 1f, 4f).toInt()), 0) })
            PdfTool.FLIP -> options(PdfOptions(tool.label, checks = listOf(PdfOptionCheck("vertical", "Mirror vertically"), PdfOptionCheck("all", "All pages")), note = flattenNote) { _, c -> editor.apply(PdfEditOperation.Flip(if (c["all"] == true) (0 until count).toList() else listOf(page), c["vertical"] != true)) })
            else -> chooseDocumentTool(tool)
        }
    }
    private fun chooseDocumentTool(tool: PdfTool) {
        when (tool) {
            PdfTool.WATERMARK -> options(PdfOptions(tool.label, fields(PdfOptionField("text", "Watermark text"), PdfOptionField("opacity", "Opacity (%)", "25"), PdfOptionField("angle", "Angle (degrees)", "35"), PdfOptionField("size", "Font size", "48"))) { v, _ ->
                require(!v["text"].isNullOrBlank()) { "Enter watermark text" }; editor.apply(PdfEditOperation.Watermark(v["text"].orEmpty(), pdfNumber(v, "opacity", 1f, 100f) / 100f, pdfNumber(v, "angle", -180f, 180f), pdfNumber(v, "size", 4f, 200f)))
            })
            PdfTool.NUMBERS -> options(PdfOptions(tool.label, fields(PdfOptionField("prefix", "Prefix (optional)"), PdfOptionField("start", "Start number", "1"), PdfOptionField("digits", "Minimum digits (Bates)", "0"), PdfOptionField("size", "Font size", "11"))) { v, _ ->
                editor.apply(PdfEditOperation.PageNumbers(v["prefix"].orEmpty(), pdfNumber(v, "start", 0f, 999999f).toInt(), pdfNumber(v, "digits", 0f, 12f).toInt(), pdfNumber(v, "size", 4f, 72f)))
            })
            PdfTool.HEADER -> options(PdfOptions(tool.label, fields(PdfOptionField("header", "Header"), PdfOptionField("footer", "Footer"), PdfOptionField("size", "Font size", "11"))) { v, _ ->
                require(!v["header"].isNullOrBlank() || !v["footer"].isNullOrBlank()) { "Enter a header or footer" }; editor.apply(PdfEditOperation.HeaderFooter(v["header"].orEmpty(), v["footer"].orEmpty(), pdfNumber(v, "size", 4f, 72f)))
            })
            PdfTool.METADATA -> options(PdfOptions(tool.label, listOf("title", "author", "subject", "keywords").map { key -> PdfOptionField(key, key.replaceFirstChar { it.uppercase() }, editor.info!!.metadata.entries.firstOrNull { it.key.equals(key, true) }?.value.orEmpty()) }) { v, _ ->
                editor.apply(PdfEditOperation.Metadata(v["title"].orEmpty(), v["author"].orEmpty(), v["subject"].orEmpty(), v["keywords"].orEmpty()))
            })
            PdfTool.BOOKMARK -> options(PdfOptions(tool.label, fields(PdfOptionField("title", "Bookmark title")), note = "Adds a bookmark to page ${editor.page + 1}.") { v, _ ->
                require(!v["title"].isNullOrBlank()) { "Enter a bookmark title" }; editor.apply(PdfEditOperation.Bookmark(v["title"].orEmpty(), editor.page))
            })
            PdfTool.REMOVE_BOOKMARKS -> options(PdfOptions(tool.label, note = "Remove all document bookmarks from the edited copy?") { _, _ -> editor.apply(PdfEditOperation.RemoveBookmarks) })
            PdfTool.FLATTEN -> options(PdfOptions(tool.label, note = "Convert current form values into page content. The fields will no longer be editable in the saved copy.") { _, _ -> editor.apply(PdfEditOperation.FlattenForms) })
            PdfTool.REMOVE_ANNOTATIONS -> options(PdfOptions(tool.label, note = "Remove annotations, including links and markup, from all pages. Text and drawings written directly onto pages remain.") { _, _ -> editor.apply(PdfEditOperation.RemoveAnnotations) })
            PdfTool.COMPRESS, PdfTool.GRAYSCALE -> options(PdfOptions(tool.label, fields(PdfOptionField("quality", "JPEG quality (%)", "75"), PdfOptionField("dpi", "Resolution (DPI)", "120")), note = "Creates image-based pages. Searchable text, interactive forms, links and vector detail are lost. This may increase some files; review the result.") { v, _ -> editor.apply(PdfEditOperation.Compress(pdfNumber(v, "quality", 10f, 100f).toInt(), pdfNumber(v, "dpi", 72f, 200f).toInt(), tool == PdfTool.GRAYSCALE)) })
            PdfTool.PROTECT -> options(PdfOptions(tool.label, fields(PdfOptionField("open", "Open password", secret = true), PdfOptionField("owner", "Owner password", secret = true)), listOf(PdfOptionCheck("print", "Allow printing", true), PdfOptionCheck("copy", "Allow copying text")), note = "Use different passwords. The open password opens the PDF; the owner password manages editing, permissions and removing protection.") { v, c ->
                val open = v["open"].orEmpty(); val owner = v["owner"].orEmpty(); require(open.isNotEmpty() && owner.isNotEmpty() && open != owner) { "Enter different nonempty open and owner passwords" }; editor.apply(PdfEditOperation.Protect(open, owner, c["print"] == true, c["copy"] == true))
            })
            PdfTool.UNLOCK -> options(PdfOptions(tool.label, fields(PdfOptionField("owner", "Owner password", secret = true)), note = "Removing protection requires the owner password. The source PDF remains protected; only the saved copy is unlocked.") { v, _ -> editor.unlock(v["owner"].orEmpty()) })
            PdfTool.PNG -> export(PdfExportKind.PAGE_PNG)
            PdfTool.JPEG -> export(PdfExportKind.PAGE_JPEG)
            PdfTool.IMAGES -> export(PdfExportKind.EMBEDDED_IMAGES)
            PdfTool.TEXT_EXPORT -> export(PdfExportKind.TEXT)
            PdfTool.SPLIT -> export(PdfExportKind.SPLIT_PAGES)
            PdfTool.SPLIT_BOOKMARKS -> options(PdfOptions(tool.label, note = "Creates PDF sections from top-level bookmarks. A document with no valid top-level bookmarks cannot be split this way.", applyLabel = "Choose output") { _, _ -> export(PdfExportKind.SPLIT_BOOKMARKS) })
            PdfTool.SPLIT_HALVES -> options(PdfOptions(tool.label, note = "Creates top and bottom page halves using PDF crop boxes. Content outside each crop can remain in the file. This is not secure content removal.", applyLabel = "Choose output") { _, _ -> export(PdfExportKind.SPLIT_HALVES) })
            else -> error("This tool requires page placement")
        }
    }
}

internal fun pdfPlacementOperation(selected: PdfPlacement, page: Int, rect: PdfRect, points: List<PdfPoint>): PdfEditOperation = when (selected.tool) {
    PdfTool.TEXT -> PdfEditOperation.Text(page, rect, selected.text, selected.size, selected.color, selected.bold)
    PdfTool.IMAGE, PdfTool.SIGN_IMAGE -> PdfEditOperation.Image(page, rect, requireNotNull(selected.image), selected.tool == PdfTool.SIGN_IMAGE)
    PdfTool.DRAW, PdfTool.SIGN -> PdfEditOperation.Ink(page, points, selected.color, selected.width)
    PdfTool.RECTANGLE, PdfTool.ELLIPSE, PdfTool.LINE, PdfTool.ARROW -> PdfEditOperation.Shape(page, rect,
        when (selected.tool) { PdfTool.ELLIPSE -> PdfShapeKind.ELLIPSE; PdfTool.LINE -> PdfShapeKind.LINE; PdfTool.ARROW -> PdfShapeKind.ARROW; else -> PdfShapeKind.RECTANGLE }, selected.color, if (selected.fill) selected.color else null, selected.width)
    PdfTool.HIGHLIGHT, PdfTool.UNDERLINE, PdfTool.STRIKEOUT -> PdfEditOperation.Markup(page, rect,
        when (selected.tool) { PdfTool.UNDERLINE -> PdfMarkupKind.UNDERLINE; PdfTool.STRIKEOUT -> PdfMarkupKind.STRIKEOUT; else -> PdfMarkupKind.HIGHLIGHT }, selected.color)
    PdfTool.WHITEOUT -> PdfEditOperation.Whiteout(page, rect)
    PdfTool.REDACT -> PdfEditOperation.Redact(page, rect, selected.text, selected.size)
    PdfTool.LINK -> PdfEditOperation.Link(page, rect, selected.text)
    PdfTool.TEXT_FIELD -> PdfEditOperation.TextField(page, rect, selected.text, selected.checked)
    PdfTool.CHECKBOX -> PdfEditOperation.Checkbox(page, rect, selected.text, selected.checked)
    PdfTool.CROP -> PdfEditOperation.Crop(page, rect)
    else -> error("Select a placement tool")
}
