package com.nixplorer.screen.viewer.pdf.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.os.Build
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import com.nixplorer.screen.main.tab.files.task.SafeFileOperations
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.Writer
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.*

/** All edits apply to an app-private copy. Export is a full rewrite, never an incremental source update. */
object PdfEditorEngine {
    const val MAX_PAGES = 200
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 256L * 1024 * 1024
    private const val MAX_PIXELS = 12_000_000L

    suspend fun inspect(context: Context, source: Uri, password: String? = null,
                        operations: List<PdfEditOperation> = emptyList(), cancelled: () -> Boolean = { false }): PdfDocumentInfo =
        withDocument(context, source, password, operations, cancelled) { document ->
            val access = document.currentAccessPermission
            PdfDocumentInfo(document.pages.mapIndexed { index, page ->
                val (width, height) = displayedSize(page)
                PdfPageInfo(index, width, height, normalizedRotation(page))
            }, document.documentCatalog.acroForm?.fieldTree?.map { field ->
                PdfFormField(field.fullyQualifiedName.orEmpty(), field.fieldType.orEmpty(), field.valueAsString.orEmpty(), field.isReadOnly)
            }.orEmpty(), document.isEncrypted, document.signatureDictionaries.isNotEmpty(), access.canModify(),
                access.canAssembleDocument(), access.canExtractContent(), mapOf(
                    "Title" to document.documentInformation.title.orEmpty(),
                    "Author" to document.documentInformation.author.orEmpty(),
                    "Subject" to document.documentInformation.subject.orEmpty(),
                    "Keywords" to document.documentInformation.keywords.orEmpty()))
        }

    suspend fun render(context: Context, source: Uri, pageIndex: Int, widthPixels: Int = 1200,
                       password: String? = null, operations: List<PdfEditOperation> = emptyList(),
                       cancelled: () -> Boolean = { false }): Bitmap =
        withDocument(context, source, password, operations, cancelled) { document ->
            val page = page(document, pageIndex)
            // PDFRenderer's bitmap scale uses default user-space units, independently of UserUnit.
            val (physicalWidth, physicalHeight) = displayedSize(page)
            val width = physicalWidth / page.userUnit
            val height = physicalHeight / page.userUnit
            val scale = minOf(widthPixels.coerceIn(200, 2400) / width,
                sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height)).toFloat())
            checkCancelled(cancelled)
            PDFRenderer(document).apply { isSubsamplingAllowed = true }.renderImage(pageIndex, scale).also {
                if (cancelled()) { it.recycle(); checkCancelled(cancelled) }
            }
        }

    suspend fun extractText(context: Context, source: Uri, password: String? = null,
                            cancelled: () -> Boolean = { false }): String {
        val job = currentCoroutineContext()[Job]
        val stop = { cancelled() || job?.isActive == false }
        return withDocument(context, source, password, emptyList(), stop) { document ->
            require(document.currentAccessPermission.canExtractContent()) { "This PDF does not permit text extraction" }
            val result = StringBuilder()
            object : PDFTextStripper() {
                private var positions = 0
                override fun processTextPosition(text: TextPosition) {
                    checkCancelled(stop)
                    require(++positions <= 2_000_000) { "Extracted text exceeds the editor limit" }
                    super.processTextPosition(text)
                }
            }.apply { sortByPosition = true }.writeText(document, object : Writer() {
                override fun write(buffer: CharArray, offset: Int, count: Int) {
                    checkCancelled(stop)
                    require(result.length.toLong() + count <= 2_000_000) { "Extracted text exceeds the editor limit" }
                    result.append(buffer, offset, count)
                }
                override fun flush() = Unit
                override fun close() = Unit
            })
            result.toString()
        }
    }

    /** Encrypted edited PDFs require an explicit Unlock or Protect operation; unchanged copies retain original bytes. */
    suspend fun export(context: Context, source: Uri, output: Uri, operations: List<PdfEditOperation>,
                       password: String? = null, cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}) {
        require(source.normalizeScheme() != output.normalizeScheme()) { "Choose a new output document; the source is never overwritten" }
        PdfOutputSafety.requireDifferentFile(context, source, output)
        val job = currentCoroutineContext()[Job]
        val stop = { cancelled() || job?.isActive == false }
        withStagedDocument(context, source, password, operations, stop) { document, originalSnapshot ->
            require(!document.isEncrypted || operations.isEmpty() || operations.any {
                it == PdfEditOperation.Unlock || it is PdfEditOperation.Protect
            }) { "This PDF is encrypted. Use Security → Remove password or Password protect before saving an edited copy" }
            val stage = File.createTempFile("pdf-edit-export-", ".pdf", context.cacheDir)
            try {
                checkCancelled(stop)
                onProgress(.5f)
                if (operations.isEmpty()) {
                    // An unchanged copy must retain its encryption, access permissions and signature bytes.
                    // PDFBox cannot full-save a loaded encrypted document without a new owner policy.
                    originalSnapshot.inputStream().use { data ->
                        FileOutputStream(stage).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancelled(stop)
                                val count = data.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                            out.fd.sync()
                        }
                    }
                } else {
                    pruneUnreachable(document)
                    saveBounded(document, stage, stop)
                }
                require(stage.length() in 1..MAX_OUTPUT_BYTES) { "Edited PDF exceeds the 256 MiB export limit" }
                checkCancelled(stop)
                // Open without truncation, compare inode identities, then write through that same descriptor.
                context.contentResolver.openFileDescriptor(source, "r").use { input ->
                    requireNotNull(input) { "Source identity could not be checked" }
                    val sourceStat = Os.fstat(input.fileDescriptor)
                    val destination = requireNotNull(context.contentResolver.openFileDescriptor(output, "rw")) { "Cannot open output document" }
                    try {
                        val targetStat = Os.fstat(destination.fileDescriptor)
                        require(sourceStat.st_ino == 0L || targetStat.st_ino == 0L ||
                            sourceStat.st_dev != targetStat.st_dev || sourceStat.st_ino != targetStat.st_ino) {
                            "Output points to the source file; choose a new document"
                        }
                        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { out ->
                            out.channel.truncate(0)
                            out.channel.position(0)
                            stage.inputStream().use { data ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                while (true) {
                                    checkCancelled(stop)
                                    val count = data.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                    copied += count
                                    onProgress(.5f + .5f * copied / stage.length())
                                }
                            }
                            out.flush()
                            out.fd.sync()
                        }
                    } finally { runCatching { destination.close() } }
                }
                onProgress(1f)
            } finally { stage.delete() }
        }
    }

    internal suspend fun <T> withDocument(context: Context, source: Uri, password: String?,
                                          operations: List<PdfEditOperation>, cancelled: () -> Boolean,
                                          block: (PDDocument) -> T): T =
        withStagedDocument(context, source, password, operations, cancelled) { document, _ -> block(document) }

    private suspend fun <T> withStagedDocument(context: Context, source: Uri, password: String?,
                                              operations: List<PdfEditOperation>, cancelled: () -> Boolean,
                                              block: (PDDocument, File) -> T): T = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val stop = { coroutine.ensureActive(); checkCancelled(cancelled) }
        PDFBoxResourceLoader.init(context.applicationContext)
        require(operations.size <= 500) { "This edit session exceeds 500 operations" }
        val input = stageSource(context, source) { stop() }
        try {
            val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 128L * 1024 * 1024).setTempDir(context.cacheDir)
            PDDocument.load(input, password.orEmpty(), memory).use { document ->
                validateDocument(document)
                // Permission checks use the ORIGINAL restrictions, before Unlock/Protect can alter them.
                val access = document.currentAccessPermission
                if (operations.isNotEmpty()) {
                    require(!document.isEncrypted || access.isOwnerPermission) { "An owner password is required to edit an encrypted PDF" }
                    operations.forEach { operation ->
                        val permitted = when (operation) {
                            is PdfEditOperation.FillForm -> access.canFillInForm()
                            is PdfEditOperation.PageOrder, is PdfEditOperation.Rotate, is PdfEditOperation.BlankPage,
                            is PdfEditOperation.Merge -> access.canAssembleDocument()
                            else -> access.canModify()
                        }
                        require(permitted) { "This PDF does not permit the requested change" }
                    }
                    // Editing an owner-opened encrypted PDF produces an unlocked working copy unless Protect is chosen.
                    document.isAllSecurityToBeRemoved = true
                }
                for (operation in operations) {
                    stop()
                    apply(context, document, operation) { stop(); false }
                    validateDocument(document)
                }
                if (operations.isNotEmpty()) pruneUnreachable(document)
                stop()
                block(document, input)
            }
        } catch (error: OutOfMemoryError) {
            throw IOException("This PDF needs more memory than is available on this device", error)
        } finally { input.delete() }
    }

    private fun stageSource(context: Context, source: Uri, check: () -> Unit): File {
        val stage = File.createTempFile("pdf-edit-input-", ".pdf", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(source)) { "Cannot read PDF" }.use { input ->
                FileOutputStream(stage).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0L
                    while (true) {
                        check()
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        require(count <= MAX_BYTES) { "PDF exceeds the 64 MiB editor limit" }
                        out.write(buffer, 0, size)
                    }
                    out.fd.sync()
                }
            }
            return stage
        } catch (error: Throwable) { stage.delete(); throw error }
    }

    private fun validateDocument(document: PDDocument) {
        require(document.numberOfPages in 1..MAX_PAGES) { "The PDF editor supports 1 to $MAX_PAGES pages" }
        document.pages.forEach { page ->
            val box = page.cropBox
            require(box.width.isFinite() && box.height.isFinite() && box.width in 1f..14_400f && box.height in 1f..14_400f) {
                "PDF page dimensions are unsupported"
            }
            require(page.userUnit.isFinite() && page.userUnit > 0f && page.userUnit <= 75_000f) { "Invalid PDF page UserUnit" }
        }
    }

    private suspend fun apply(context: Context, document: PDDocument, operation: PdfEditOperation, cancelled: () -> Boolean) {
        when (operation) {
            is PdfEditOperation.Text -> addText(document, operation)
            is PdfEditOperation.Image -> addImage(context, document, operation, cancelled)
            is PdfEditOperation.Ink -> draw(document, operation.page) { stream, page ->
                require(operation.points.size in 2..20_000) { "A drawing needs 2 to 20,000 points" }
                lineStyle(stream, operation.color, operation.width)
                val (w, h) = displayedSize(page)
                operation.points.forEachIndexed { index, point ->
                    validatePoint(point)
                    if (index == 0) stream.moveTo(point.x * w, point.y * h) else stream.lineTo(point.x * w, point.y * h)
                }
                stream.stroke()
            }
            is PdfEditOperation.Shape -> draw(document, operation.page) { stream, page ->
                val rect = if (operation.kind == PdfShapeKind.LINE || operation.kind == PdfShapeKind.ARROW) {
                    val bounds = operation.rect
                    validatePoint(PdfPoint(bounds.left, bounds.top)); validatePoint(PdfPoint(bounds.right, bounds.bottom))
                    require(bounds.right >= bounds.left && bounds.bottom >= bounds.top &&
                        (bounds.right > bounds.left || bounds.bottom > bounds.top)) { "Choose two distinct line endpoints" }
                    val (w, h) = displayedSize(page)
                    PDRectangle(bounds.left * w, bounds.top * h, (bounds.right - bounds.left) * w, (bounds.bottom - bounds.top) * h)
                } else displayRect(page, operation.rect)
                lineStyle(stream, operation.color, operation.width)
                operation.fill?.let { color(stream, it, false) }
                val x = rect.lowerLeftX; val y = rect.lowerLeftY; val w = rect.width; val h = rect.height
                when (operation.kind) {
                    PdfShapeKind.RECTANGLE -> stream.addRect(x, y, w, h)
                    PdfShapeKind.ELLIPSE -> {
                        val k = .55228475f
                        stream.moveTo(x + w / 2, y)
                        stream.curveTo(x + w / 2 + k * w / 2, y, x + w, y + h / 2 - k * h / 2, x + w, y + h / 2)
                        stream.curveTo(x + w, y + h / 2 + k * h / 2, x + w / 2 + k * w / 2, y + h, x + w / 2, y + h)
                        stream.curveTo(x + w / 2 - k * w / 2, y + h, x, y + h / 2 + k * h / 2, x, y + h / 2)
                        stream.curveTo(x, y + h / 2 - k * h / 2, x + w / 2 - k * w / 2, y, x + w / 2, y)
                        stream.closePath()
                    }
                    PdfShapeKind.LINE, PdfShapeKind.ARROW -> {
                        stream.moveTo(x, y); stream.lineTo(x + w, y + h)
                        if (operation.kind == PdfShapeKind.ARROW) {
                            val angle = atan2(h, w); val length = minOf(16f, sqrt(w * w + h * h) / 3)
                            for (offset in listOf(-.5f, .5f)) {
                                stream.moveTo(x + w, y + h)
                                stream.lineTo(x + w - length * cos(angle + offset), y + h - length * sin(angle + offset))
                            }
                        }
                    }
                }
                if (operation.fill != null && operation.kind in setOf(PdfShapeKind.RECTANGLE, PdfShapeKind.ELLIPSE)) stream.fillAndStroke() else stream.stroke()
            }
            is PdfEditOperation.Markup -> {
                val page = page(document, operation.page)
                val rect = displayRect(page, operation.rect)
                val transform = displayMatrix(page)
                val points = listOf(rect.lowerLeftX to rect.lowerLeftY, rect.upperRightX to rect.lowerLeftY,
                    rect.lowerLeftX to rect.upperRightY, rect.upperRightX to rect.upperRightY)
                    .map { (x, y) -> transform.transformPoint(x, y) }
                val subtype = when (operation.kind) {
                    PdfMarkupKind.HIGHLIGHT -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                    PdfMarkupKind.UNDERLINE -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                    PdfMarkupKind.STRIKEOUT -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
                }
                val annotation = PDAnnotationTextMarkup(subtype).apply {
                    rectangle = physicalRect(page, operation.rect)
                    quadPoints = points.flatMap { listOf(it.x, it.y) }.toFloatArray()
                    val tint = operation.color.toInt()
                    color = PDColor(floatArrayOf(Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f), PDDeviceRGB.INSTANCE)
                    constantOpacity = if (operation.kind == PdfMarkupKind.HIGHLIGHT) .35f else 1f
                    isPrinted = true
                    this.page = page
                    contents = operation.kind.name.lowercase().replaceFirstChar { it.uppercase() }
                }
                page.annotations.add(annotation)
                annotation.constructAppearances(document)
            }
            is PdfEditOperation.Whiteout -> draw(document, operation.page) { stream, page ->
                val rect = displayRect(page, operation.rect)
                stream.setNonStrokingColor(255, 255, 255); stream.addRect(rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height); stream.fill()
            }
            is PdfEditOperation.Redact -> redact(document, operation, cancelled)
            is PdfEditOperation.Link -> {
                val parsed = java.net.URI(operation.url)
                require(parsed.scheme?.lowercase() in setOf("https", "http", "mailto", "tel")) { "Links must use https, http, mailto or tel" }
                val page = page(document, operation.page)
                page.annotations.add(PDAnnotationLink().apply {
                    rectangle = physicalRect(page, operation.rect)
                    action = PDActionURI().apply { uri = operation.url }
                    isPrinted = true
                    border = COSArray().apply { add(COSInteger.ZERO); add(COSInteger.ZERO); add(COSInteger.ZERO) }
                })
            }
            is PdfEditOperation.FillForm -> {
                val form = requireNotNull(document.documentCatalog.acroForm) { "This PDF has no form fields" }
                require(!form.hasXFA()) { "XFA forms are not supported" }
                operation.values.forEach { (name, value) ->
                    val field = requireNotNull(form.getField(name)) { "Form field not found: $name" }
                    require(!field.isReadOnly && field !is PDSignatureField) { "This field cannot be filled" }
                    if (field is PDCheckBox) {
                        if (value.equals("true", true) || value == field.onValue || value == "1") field.check() else field.unCheck()
                    } else field.setValue(value)
                }
                form.refreshAppearances()
            }
            is PdfEditOperation.TextField -> createField(document, operation.page, operation.rect, operation.name, operation.multiline, null)
            is PdfEditOperation.Checkbox -> createField(document, operation.page, operation.rect, operation.name, false, operation.checked)
            is PdfEditOperation.PageOrder -> pageOrder(document, operation.pages)
            is PdfEditOperation.Rotate -> {
                require(operation.degrees % 90 == 0) { "Rotation must be in 90 degree steps" }
                operation.pages.distinct().forEach { page(document, it).let { page -> page.rotation = (normalizedRotation(page) + operation.degrees).mod(360) } }
            }
            is PdfEditOperation.BlankPage -> {
                require(operation.width in 72f..14_400f && operation.height in 72f..14_400f) { "Invalid blank page size" }
                require(operation.afterPage in -1 until document.numberOfPages) { "Choose an existing insertion position" }
                val blank = PDPage(PDRectangle(operation.width, operation.height))
                if (operation.afterPage < 0) document.pages.insertBefore(blank, document.getPage(0))
                else document.pages.insertAfter(blank, document.getPage(operation.afterPage))
            }
            is PdfEditOperation.Merge -> {
                require(operation.sources.size in 1..20) { "Merge accepts 1 to 20 additional PDFs" }
                for (source in operation.sources) withDocument(context, source, null, emptyList(), cancelled) { incoming ->
                    require(incoming.currentAccessPermission.canAssembleDocument()) { "An input PDF does not allow merging" }
                    PdfDocumentTools.flattenVisibleAnnotations(incoming) { checkCancelled(cancelled) }
                    incoming.pages.forEach { importPage(document, it) }
                }
            }
            is PdfEditOperation.Crop -> {
                val page = page(document, operation.page)
                page.cropBox = physicalRect(page, operation.rect)
            }
            else -> PdfDocumentTools.apply(context, document, operation, cancelled)
        }
    }

    private fun addText(document: PDDocument, operation: PdfEditOperation.Text) {
        require(operation.text.isNotBlank() && operation.text.length <= 10_000) { "Text must contain 1 to 10,000 characters" }
        require(operation.size in 4f..200f) { "Text size must be 4 to 200 points" }
        val font = font(document, operation.bold)
        // Reject unavailable glyphs explicitly rather than silently replacing the user's text.
        operation.text.replace("\n", "").replace("\r", "").replace("\t", " ").let(font::encode)
        draw(document, operation.page) { stream, page ->
            val rect = displayRect(page, operation.rect)
            color(stream, operation.color, false)
            stream.beginText(); stream.setFont(font, operation.size)
            stream.setTextMatrix(Matrix(1f, 0f, 0f, -1f, rect.lowerLeftX, rect.lowerLeftY + operation.size))
            stream.setLeading(operation.size * 1.25f)
            operation.text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
                if (index > 0) stream.newLineAtOffset(0f, -operation.size * 1.25f)
                stream.showText(line.replace('\t', ' '))
            }
            stream.endText()
        }
    }

    private fun addImage(context: Context, document: PDDocument, operation: PdfEditOperation.Image, cancelled: () -> Boolean) {
        val file = stageSource(context, operation.uri) { checkCancelled(cancelled) }
        var bitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) {
                "Image exceeds the 12 megapixel editor limit or cannot be decoded"
            }
            val image = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
            } else requireNotNull(BitmapFactory.decodeFile(file.path)) { "Image cannot be decoded" }
            bitmap = image
            val objectImage = LosslessFactory.createFromImage(document, image)
            draw(document, operation.page) { stream, page ->
                val rect = displayRect(page, operation.rect)
                stream.drawImage(objectImage, Matrix(rect.width, 0f, 0f, -rect.height, rect.lowerLeftX, rect.upperRightY))
            }
        } finally { bitmap?.recycle(); file.delete() }
    }

    private fun redact(document: PDDocument, operation: PdfEditOperation.Redact, cancelled: () -> Boolean) {
        val old = page(document, operation.page)
        validateRect(operation.rect)
        val (width, height) = displayedSize(old)
        require(width.toDouble() * height * 4 <= MAX_PIXELS) { "This page is too large for safe 144 dpi redaction" }
        checkCancelled(cancelled)
        // Flatten visible form values before rendering. Afterwards no hidden field values may survive.
        document.documentCatalog.acroForm?.let { form ->
            require(!form.hasXFA()) { "XFA documents cannot be redacted locally" }
            form.flatten()
        }
        // Materialize inherited resources on surviving pages, then remove the parent-tree copies.
        // Otherwise an unused XObject of the redacted page could remain reachable via /Pages /Resources.
        document.pages.forEach { it.resources = it.resources ?: PDResources() }
        val pageNodes = java.util.ArrayDeque<COSDictionary>()
        val visitedNodes = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        pageNodes.add(document.documentCatalog.pages.cosObject)
        while (pageNodes.isNotEmpty()) {
            checkCancelled(cancelled)
            val node = pageNodes.removeFirst()
            if (!visitedNodes.add(node)) continue
            require(visitedNodes.size <= 10_000) { "PDF page tree exceeds the editor limit" }
            if (node.getCOSName(COSName.TYPE) != COSName.PAGES) continue
            node.removeItem(COSName.RESOURCES)
            node.getCOSArray(COSName.KIDS)?.forEach { child ->
                val direct = if (child is COSObject) child.`object` else child
                if (direct is COSDictionary) pageNodes.add(direct)
            }
        }
        val bitmap = PDFRenderer(document).renderImage(operation.page, 2f * old.userUnit)
        try {
            Canvas(bitmap).drawRect(operation.rect.left * bitmap.width, operation.rect.top * bitmap.height,
                operation.rect.right * bitmap.width, operation.rect.bottom * bitmap.height, Paint().apply { color = Color.WHITE })
            val replacement = PDPage(PDRectangle(width, height))
            val image = LosslessFactory.createFromImage(document, bitmap)
            PDPageContentStream(document, replacement).use { stream -> stream.drawImage(image, 0f, 0f, width, height) }
            // Keep the page identity (bookmarks may refer to it), replacing every content/resource reference.
            val parent = old.cosObject.getItem(COSName.PARENT)
            old.cosObject.clear()
            old.cosObject.addAll(replacement.cosObject)
            if (parent != null) old.cosObject.setItem(COSName.PARENT, parent)
            document.documentCatalog.acroForm = null
            // Tagged text, embedded files, XMP and old destinations may retain discarded page data.
            for (key in listOf(COSName.STRUCT_TREE_ROOT, COSName.METADATA, COSName.NAMES, COSName.DESTS, COSName.OUTLINES,
                COSName.OPEN_ACTION, COSName.AA, COSName.getPDFName("AF"), COSName.getPDFName("PieceInfo"),
                COSName.getPDFName("Perms"))) document.documentCatalog.cosObject.removeItem(key)
            document.documentInformation = PDDocumentInformation()
            if (operation.replacement.isNotBlank()) addText(document,
                PdfEditOperation.Text(operation.page, operation.rect, operation.replacement, operation.size))
            pruneUnreachable(document)
        } finally { bitmap.recycle() }
    }

    private fun createField(document: PDDocument, pageIndex: Int, rect: PdfRect, name: String, multiline: Boolean, checked: Boolean?) {
        require(name.isNotBlank() && name.length <= 100 && '.' !in name) { "Use a unique field name without periods (1 to 100 characters)" }
        val page = page(document, pageIndex)
        val form = document.documentCatalog.acroForm ?: PDAcroForm(document).also { document.documentCatalog.acroForm = it }
        require(form.getField(name) == null && !form.hasXFA()) { "Field name exists or this PDF uses unsupported XFA forms" }
        val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
        resources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        form.defaultAppearance = "/Helv 12 Tf 0 g"
        val field: PDTerminalField = if (checked == null) PDTextField(form).apply {
            isMultiline = multiline; defaultAppearance = "/Helv 12 Tf 0 g"
        } else PDCheckBox(form)
        field.partialName = name
        val widget = field.widgets.first()
        widget.rectangle = physicalRect(page, rect)
        widget.page = page
        widget.isPrinted = true
        widget.appearanceCharacteristics = PDAppearanceCharacteristicsDictionary(COSDictionary()).apply {
            // /MK /R is counterclockwise, cancelling the page's clockwise display rotation.
            rotation = normalizedRotation(page)
            borderColour = PDColor(floatArrayOf(0f, 0f, 0f), PDDeviceRGB.INSTANCE)
            background = PDColor(floatArrayOf(1f, 1f, 1f), PDDeviceRGB.INSTANCE)
        }
        widget.borderStyle = PDBorderStyleDictionary().apply { width = 1f / page.userUnit }
        page.annotations.add(widget)
        form.fields.add(field)
        if (field is PDCheckBox) {
            val bounds = widget.rectangle
            val appearance = PDAppearanceDictionary()
            val states = COSDictionary()
            for (on in listOf(false, true)) {
                val stream = PDAppearanceStream(document).apply { bBox = PDRectangle(bounds.width, bounds.height); this.resources = PDResources() }
                PDPageContentStream(document, stream).use { canvas ->
                    canvas.setStrokingColor(0, 0, 0); canvas.setLineWidth(1f)
                    canvas.addRect(.5f, .5f, maxOf(1f, bounds.width - 1), maxOf(1f, bounds.height - 1)); canvas.stroke()
                    if (on) { canvas.moveTo(2f, 2f); canvas.lineTo(bounds.width - 2, bounds.height - 2); canvas.moveTo(2f, bounds.height - 2); canvas.lineTo(bounds.width - 2, 2f); canvas.stroke() }
                }
                states.setItem(if (on) "Yes" else "Off", stream)
            }
            appearance.setNormalAppearance(PDAppearanceEntry(states))
            widget.appearance = appearance
            if (checked == true) field.check() else field.unCheck()
        } else (field as PDTextField).value = ""
    }

    private fun pageOrder(document: PDDocument, indices: List<Int>) {
        require(indices.isNotEmpty() && indices.size <= MAX_PAGES) { "Keep 1 to $MAX_PAGES pages" }
        val original = document.pages.toList()
        require(indices.all { it in original.indices }) { "A selected page no longer exists" }
        PdfDocumentTools.flattenVisibleAnnotations(document) { }
        // Clone duplicates before removing the old page tree; each copy gets its own page dictionary.
        val seen = mutableSetOf<Int>()
        val selected = indices.map { index ->
            if (seen.add(index)) original[index] else {
                val copy = PDPage(PDFCloneUtility(document).cloneForNewDocument(original[index].cosObject) as COSDictionary)
                copy.cosObject.removeItem(COSName.PARENT)
                copy
            }
        }
        original.forEach(document::removePage)
        selected.forEach(document::addPage)
        document.documentCatalog.documentOutline = null
        document.documentCatalog.acroForm = null
        for (key in listOf(COSName.NAMES, COSName.DESTS, COSName.STRUCT_TREE_ROOT)) document.documentCatalog.cosObject.removeItem(key)
    }

    private fun importPage(document: PDDocument, source: PDPage) {
        val imported = document.importPage(source)
        imported.resources = PDResources(PDFCloneUtility(document).cloneForNewDocument(source.resources?.cosObject ?: COSDictionary()) as COSDictionary)
        imported.annotations = mutableListOf() // Imported interactive fields/links cannot safely retain foreign document references.
    }

    private fun draw(document: PDDocument, index: Int, block: (PDPageContentStream, PDPage) -> Unit) {
        val page = page(document, index)
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
            stream.saveGraphicsState(); stream.transform(displayMatrix(page)); block(stream, page); stream.restoreGraphicsState()
        }
    }

    private fun font(document: PDDocument, bold: Boolean): PDFont {
        val path = if (bold) "/system/fonts/Roboto-Bold.ttf" else "/system/fonts/Roboto-Regular.ttf"
        return if (File(path).isFile) PDType0Font.load(document, File(path))
            else if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
    }

    private fun lineStyle(stream: PDPageContentStream, value: Long, width: Float) {
        require(width.isFinite() && width in .25f..50f) { "Line width must be 0.25 to 50 points" }
        color(stream, value, true); stream.setLineWidth(width); stream.setLineCapStyle(1); stream.setLineJoinStyle(1)
    }

    private fun color(stream: PDPageContentStream, value: Long, stroke: Boolean) {
        val color = value.toInt()
        if (stroke) stream.setStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
        else stream.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun page(document: PDDocument, index: Int): PDPage {
        require(index in 0 until document.numberOfPages) { "Page no longer exists" }
        return document.getPage(index)
    }

    private fun normalizedRotation(page: PDPage) = page.rotation.mod(360).also { require(it % 90 == 0) { "Unsupported page rotation" } }
    private fun displayedSize(page: PDPage): Pair<Float, Float> = page.cropBox.let {
        val unit = page.userUnit
        if (normalizedRotation(page) % 180 == 0) it.width * unit to it.height * unit else it.height * unit to it.width * unit
    }
    private fun displayMatrix(page: PDPage): Matrix = page.cropBox.let { box ->
        val inverse = 1f / page.userUnit
        when (normalizedRotation(page)) {
            90 -> Matrix(0f, inverse, inverse, 0f, box.lowerLeftX, box.lowerLeftY)
            180 -> Matrix(-inverse, 0f, 0f, inverse, box.upperRightX, box.lowerLeftY)
            270 -> Matrix(0f, -inverse, -inverse, 0f, box.upperRightX, box.upperRightY)
            else -> Matrix(inverse, 0f, 0f, -inverse, box.lowerLeftX, box.upperRightY)
        }
    }
    private fun displayRect(page: PDPage, rect: PdfRect): PDRectangle {
        validateRect(rect)
        val (width, height) = displayedSize(page)
        return PDRectangle(rect.left * width, rect.top * height, (rect.right - rect.left) * width, (rect.bottom - rect.top) * height)
    }
    private fun physicalRect(page: PDPage, rect: PdfRect): PDRectangle {
        val display = displayRect(page, rect)
        val transform = displayMatrix(page)
        val a = transform.transformPoint(display.lowerLeftX, display.lowerLeftY)
        val b = transform.transformPoint(display.upperRightX, display.upperRightY)
        return PDRectangle(minOf(a.x, b.x), minOf(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y))
    }
    private fun validatePoint(point: PdfPoint) { require(point.x.isFinite() && point.y.isFinite() && point.x in 0f..1f && point.y in 0f..1f) { "Position must be inside the page" } }
    private fun validateRect(rect: PdfRect) {
        validatePoint(PdfPoint(rect.left, rect.top)); validatePoint(PdfPoint(rect.right, rect.bottom))
        require(rect.right > rect.left && rect.bottom > rect.top) { "Choose a non-empty area inside the page" }
    }
    private fun checkCancelled(cancelled: () -> Boolean) = SafeFileOperations.checkCancelled(cancelled)

    /** Enforce the output bound during serialization, before large intermediate PDFs can exhaust disk. */
    internal fun saveBounded(document: PDDocument, file: File, cancelled: () -> Boolean) {
        FileOutputStream(file).use { raw ->
            val output = object : FilterOutputStream(raw) {
                private var count = 0L
                private fun reserve(length: Int) {
                    checkCancelled(cancelled)
                    require(length >= 0 && count + length <= MAX_OUTPUT_BYTES) { "Edited PDF exceeds the 256 MiB export limit" }
                    count += length
                }
                override fun write(value: Int) { reserve(1); out.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    reserve(length)
                    out.write(bytes, offset, length)
                }
            }
            document.save(output)
        }
    }

    /** Full-save only includes objects reachable from the current trailer, eliminating discarded content streams. */
    internal fun pruneUnreachable(document: PDDocument) {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val queue = java.util.ArrayDeque<COSBase>()
        queue.add(document.document.trailer)
        while (queue.isNotEmpty()) {
            val value = queue.removeFirst()
            if (!seen.add(value)) continue
            require(seen.size <= 500_000) { "PDF object graph exceeds the editor limit" }
            when (value) {
                is COSObject -> value.`object`?.let(queue::add)
                is COSDictionary -> value.values.forEach { child -> child?.let(queue::add) }
                is COSArray -> value.forEach { child -> child?.let(queue::add) }
            }
        }
        document.document.objects.toList().filter { it !in seen && it.`object` !in seen }.forEach {
            document.document.removeObject(COSObjectKey(it))
        }
    }
}
