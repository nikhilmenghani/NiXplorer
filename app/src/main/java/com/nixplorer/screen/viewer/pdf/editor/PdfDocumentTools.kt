package com.nixplorer.screen.viewer.pdf.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Local document-wide operations. The caller owns the loaded document and transactional save. */
internal object PdfDocumentTools {
    private const val MAX_PAGES = 500
    private const val MAX_RENDER_PIXELS = 8_000_000L
    private const val MAX_TEXT_CHARACTERS = 4096
    private const val FONT_ASSET = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"

    suspend fun apply(context: Context, document: PDDocument, operation: PdfEditOperation, cancelled: () -> Boolean) {
        val coroutine = currentCoroutineContext()
        val checkCancelled = {
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("PDF operation cancelled")
        }
        checkCancelled()
        require(document.numberOfPages in 1..MAX_PAGES) { "PDF editing supports up to $MAX_PAGES pages" }
        when (operation) {
            is PdfEditOperation.Watermark -> watermark(document, operation, checkCancelled)
            is PdfEditOperation.PageNumbers -> pageNumbers(context, document, operation, checkCancelled)
            is PdfEditOperation.HeaderFooter -> headerFooter(context, document, operation, checkCancelled)
            is PdfEditOperation.Metadata -> metadata(document, operation)
            PdfEditOperation.FlattenForms -> flattenForms(document, checkCancelled)
            PdfEditOperation.RemoveAnnotations -> removeAnnotations(document, checkCancelled)
            is PdfEditOperation.Protect -> protect(document, operation)
            PdfEditOperation.Unlock -> {
                require(document.currentAccessPermission.isOwnerPermission) { "Reopen the PDF with its owner password to remove protection" }
                document.isAllSecurityToBeRemoved = true
            }
            is PdfEditOperation.Compress -> rasterize(document, operation, checkCancelled)
            is PdfEditOperation.Resize -> resize(document, operation, checkCancelled)
            is PdfEditOperation.NUp -> nUp(document, operation, checkCancelled)
            is PdfEditOperation.Flip -> flip(document, operation, checkCancelled)
            is PdfEditOperation.Bookmark -> {
                validateText(operation.title)
                require(operation.title.isNotBlank()) { "Enter a bookmark title" }
                require(operation.page in 0 until document.numberOfPages) { "The bookmark page is out of range" }
                val outline = document.documentCatalog.documentOutline ?: PDDocumentOutline().also { document.documentCatalog.documentOutline = it }
                outline.addLast(PDOutlineItem().apply { title = operation.title; setDestination(document.getPage(operation.page)) })
                outline.openNode()
            }
            PdfEditOperation.RemoveBookmarks -> document.documentCatalog.documentOutline = null
            else -> error("This PDF operation is handled by the document editor")
        }
        checkCancelled()
    }

    private fun watermark(document: PDDocument, operation: PdfEditOperation.Watermark, check: () -> Unit) {
        validateText(operation.text)
        require(operation.text.isNotBlank()) { "Enter watermark text" }
        require(operation.opacity.isFinite() && operation.opacity in 0f..1f) { "Watermark opacity must be between 0 and 1" }
        require(operation.angle.isFinite() && operation.angle in -360f..360f) { "Invalid watermark angle" }
        require(operation.size.isFinite() && operation.size in 4f..300f) { "Watermark font size must be between 4 and 300 points" }
        // Android shapes all scripts and font fallbacks. Only the new watermark is an image;
        // existing document text stays searchable, and no unsupported glyph is silently dropped.
        val bitmap = textBitmap(operation.text, operation.size, operation.color.toInt(), 1800)
        try {
            val image = LosslessFactory.createFromImage(document, bitmap)
            for (page in document.pages) {
                check()
                val geometry = geometry(page)
                val radians = Math.toRadians(operation.angle.toDouble())
                val naturalWidth = bitmap.width / 2f
                val naturalHeight = bitmap.height / 2f
                val rotatedWidth = (kotlin.math.abs(cos(radians)) * naturalWidth + kotlin.math.abs(sin(radians)) * naturalHeight).toFloat()
                val rotatedHeight = (kotlin.math.abs(sin(radians)) * naturalWidth + kotlin.math.abs(cos(radians)) * naturalHeight).toFloat()
                val fit = min(1f, min(geometry.width * .88f / rotatedWidth, geometry.height * .88f / rotatedHeight))
                val width = naturalWidth * fit
                val height = naturalHeight * fit
                PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { stream ->
                    stream.saveGraphicsState()
                    stream.transform(geometry.toPage)
                    stream.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = operation.opacity })
                    stream.transform(Matrix.getRotateInstance(radians, geometry.width / 2, geometry.height / 2))
                    stream.drawImage(image, -width / 2, -height / 2, width, height)
                    stream.restoreGraphicsState()
                }
            }
        } finally { bitmap.recycle() }
    }

    private fun pageNumbers(context: Context, document: PDDocument, operation: PdfEditOperation.PageNumbers, check: () -> Unit) {
        validateText(operation.prefix)
        require(operation.start >= 0 && operation.start.toLong() + document.numberOfPages <= Int.MAX_VALUE) { "Invalid starting page number" }
        require(operation.digits in 0..12) { "Bates numbering supports 0 to 12 digits" }
        validateFontSize(operation.size)
        val font = context.assets.open(FONT_ASSET).use { PDType0Font.load(document, it) }
        for ((index, page) in document.pages.withIndex()) {
            check()
            val label = operation.prefix + (operation.start + index).toString().padStart(operation.digits, '0')
            val view = geometry(page)
            addLabel(document, page, view, font, label, operation.size, max(8f, operation.size * 1.4f))
        }
    }

    private fun headerFooter(context: Context, document: PDDocument, operation: PdfEditOperation.HeaderFooter, check: () -> Unit) {
        validateText(operation.header); validateText(operation.footer); validateFontSize(operation.size)
        require(operation.header.isNotBlank() || operation.footer.isNotBlank()) { "Enter a header or footer" }
        val font = context.assets.open(FONT_ASSET).use { PDType0Font.load(document, it) }
        for (page in document.pages) {
            check()
            val view = geometry(page)
            if (operation.header.isNotBlank()) addLabel(document, page, view, font, operation.header, operation.size, view.height - operation.size * 2f)
            if (operation.footer.isNotBlank()) addLabel(document, page, view, font, operation.footer, operation.size, max(8f, operation.size * 1.4f))
        }
    }

    private fun addLabel(document: PDDocument, page: PDPage, view: Geometry, font: PDType0Font, text: String, size: Float, baseline: Float) {
        val label = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
        val width = try { font.getStringWidth(label) / 1000 * size } catch (_: IllegalArgumentException) { -1f }
        PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { stream ->
            stream.saveGraphicsState()
            stream.transform(view.toPage)
            if (width >= 0f) {
                val fittedSize = size * min(1f, (view.width - 24f).coerceAtLeast(1f) / width.coerceAtLeast(1f))
                val fittedWidth = width * fittedSize / size
                stream.setNonStrokingColor(0f)
                stream.beginText(); stream.setFont(font, fittedSize)
                stream.newLineAtOffset((view.width - fittedWidth) / 2f, baseline.coerceIn(1f, (view.height - fittedSize).coerceAtLeast(1f)))
                stream.showText(label); stream.endText()
            } else {
                val bitmap = textBitmap(label, size, Color.BLACK, 1800)
                try {
                    val image = LosslessFactory.createFromImage(document, bitmap)
                    val scale = min(.5f, (view.width - 24f).coerceAtLeast(1f) / bitmap.width)
                    val imageWidth = bitmap.width * scale
                    val imageHeight = bitmap.height * scale
                    stream.drawImage(image, (view.width - imageWidth) / 2, (baseline - size * .25f).coerceAtLeast(0f), imageWidth, imageHeight)
                } finally { bitmap.recycle() }
            }
            stream.restoreGraphicsState()
        }
    }

    private fun metadata(document: PDDocument, operation: PdfEditOperation.Metadata) {
        listOf(operation.title, operation.author, operation.subject, operation.keywords).forEach(::validateText)
        val information = document.documentInformation ?: PDDocumentInformation()
        information.title = operation.title
        information.author = operation.author
        information.subject = operation.subject
        information.keywords = operation.keywords
        document.documentInformation = information
        // Prevent an older XMP metadata packet from overriding the newly supplied document values.
        document.documentCatalog.metadata = null
    }

    private fun flattenForms(document: PDDocument, check: () -> Unit) {
        check()
        val form = document.documentCatalog.acroForm ?: return
        require(!form.hasXFA()) { "XFA forms cannot be flattened locally" }
        val fields = form.fields.toList()
        require(fields.size <= 5000) { "The PDF contains too many form fields" }
        form.flatten(fields, true)
        check()
        if (form.fields.isEmpty()) document.documentCatalog.acroForm = null
    }

    private fun removeAnnotations(document: PDDocument, check: () -> Unit) {
        for (page in document.pages) {
            check()
            // Widgets are live form controls rather than document comments/markups.
            page.annotations = page.annotations.filter { it is PDAnnotationWidget }
        }
    }

    private fun protect(document: PDDocument, operation: PdfEditOperation.Protect) {
        require(operation.userPassword.isNotEmpty()) { "Enter an open password" }
        require(operation.ownerPassword.isNotEmpty()) { "Enter a separate owner password for managing PDF permissions" }
        require(operation.userPassword != operation.ownerPassword) { "Use different open and owner passwords" }
        require(operation.userPassword.toByteArray(Charsets.UTF_8).size <= 127 && operation.ownerPassword.toByteArray(Charsets.UTF_8).size <= 127) { "PDF passwords must be at most 127 UTF-8 bytes" }
        require(document.currentAccessPermission.isOwnerPermission) { "Reopen the PDF with its owner password to change protection" }
        val permissions = AccessPermission().apply {
            setCanPrint(operation.allowPrinting)
            setCanPrintDegraded(operation.allowPrinting)
            setCanExtractContent(operation.allowCopy)
            setCanExtractForAccessibility(true)
            setCanModify(false); setCanModifyAnnotations(false); setCanFillInForm(false); setCanAssembleDocument(false)
        }
        val policy = StandardProtectionPolicy(operation.ownerPassword, operation.userPassword, permissions).apply {
            encryptionKeyLength = 256; isPreferAES = true
        }
        document.isAllSecurityToBeRemoved = false
        document.protect(policy)
    }

    private fun rasterize(document: PDDocument, operation: PdfEditOperation.Compress, check: () -> Unit) {
        require(operation.quality in 10..100) { "JPEG quality must be between 10 and 100" }
        require(operation.dpi in 36..300) { "PDF resolution must be between 36 and 300 DPI" }
        val renderer = PDFRenderer(document).apply { isSubsamplingAllowed = true }
        val replacement = ArrayList<PDPage>(document.numberOfPages)
        for (index in 0 until document.numberOfPages) {
            check()
            val page = document.getPage(index)
            val view = geometry(page)
            val pixelScale = min(operation.dpi / 72f, sqrt(MAX_RENDER_PIXELS.toDouble() / (view.width * view.height)).toFloat())
            require(pixelScale > 0f && pixelScale.isFinite()) { "Invalid PDF page dimensions" }
            val bitmap = renderer.renderImage(index, pixelScale * page.userUnit, ImageType.RGB)
            try {
                check()
                require(bitmap.width.toLong() * bitmap.height <= MAX_RENDER_PIXELS + 10_000) { "PDF page exceeds the render memory limit" }
                if (operation.grayscale) {
                    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
                    // Rendered RGB bitmaps are mutable; drawing through a color matrix keeps one page resident.
                    Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, paint)
                }
                val image = JPEGFactory.createFromImage(document, bitmap, operation.quality / 100f)
                val output = PDPage(PDRectangle(view.width, view.height))
                PDPageContentStream(document, output).use { it.drawImage(image, 0f, 0f, view.width, view.height) }
                replacement += output
            } finally { bitmap.recycle() }
        }
        check()
        replacePages(document, replacement)
    }

    private fun resize(document: PDDocument, operation: PdfEditOperation.Resize, check: () -> Unit) {
        require(operation.width.isFinite() && operation.height.isFinite() && operation.width in 36f..14_400f && operation.height in 36f..14_400f) { "Page dimensions must be between 36 and 14,400 points" }
        flattenVisibleAnnotations(document, check)
        val pages = document.pages.toList()
        val replacement = pages.map { source ->
            check()
            val view = geometry(source)
            val form = pageForm(document, source, check)
            val output = PDPage(PDRectangle(operation.width, operation.height))
            val scale = min(operation.width / view.width, operation.height / view.height)
            PDPageContentStream(document, output).use { stream ->
                stream.transform(Matrix(scale, 0f, 0f, scale, (operation.width - view.width * scale) / 2, (operation.height - view.height * scale) / 2))
                stream.drawForm(form)
            }
            output
        }
        check()
        replacePages(document, replacement)
    }

    private fun nUp(document: PDDocument, operation: PdfEditOperation.NUp, check: () -> Unit) {
        require(operation.columns in 1..4 && operation.rows in 1..4 && operation.columns * operation.rows in 2..16) { "N-up supports 2 to 16 pages per sheet" }
        flattenVisibleAnnotations(document, check)
        val pages = document.pages.toList()
        val count = operation.columns * operation.rows
        val width = if (operation.columns > operation.rows) PDRectangle.A4.height else PDRectangle.A4.width
        val height = if (operation.columns > operation.rows) PDRectangle.A4.width else PDRectangle.A4.height
        val margin = 18f
        val gap = 9f
        val cellWidth = (width - margin * 2 - gap * (operation.columns - 1)) / operation.columns
        val cellHeight = (height - margin * 2 - gap * (operation.rows - 1)) / operation.rows
        val replacement = ArrayList<PDPage>()
        for (group in pages.indices.step(count)) {
            check()
            val output = PDPage(PDRectangle(width, height))
            PDPageContentStream(document, output).use { stream ->
                for (slot in 0 until min(count, pages.size - group)) {
                    check()
                    val page = pages[group + slot]
                    val view = geometry(page)
                    val form = pageForm(document, page, check)
                    val scale = min(cellWidth / view.width, cellHeight / view.height)
                    val x = margin + (slot % operation.columns) * (cellWidth + gap) + (cellWidth - view.width * scale) / 2
                    val y = height - margin - (slot / operation.columns + 1) * cellHeight - (slot / operation.columns) * gap + (cellHeight - view.height * scale) / 2
                    stream.saveGraphicsState(); stream.transform(Matrix(scale, 0f, 0f, scale, x, y)); stream.drawForm(form); stream.restoreGraphicsState()
                }
            }
            replacement += output
        }
        check()
        replacePages(document, replacement)
    }

    private fun flip(document: PDDocument, operation: PdfEditOperation.Flip, check: () -> Unit) {
        require(operation.pages.isNotEmpty() && operation.pages.size <= MAX_PAGES && operation.pages.all { it in 0 until document.numberOfPages }) { "Choose valid pages to mirror" }
        flattenVisibleAnnotations(document, check)
        for (index in operation.pages.distinct()) {
            check()
            val page = document.getPage(index)
            val view = geometry(page)
            // Keep the page object, crop boxes and rotation so existing bookmark destinations stay valid.
            val original = pageForm(document, page, check)
            page.resources = PDResources()
            PDPageContentStream(document, page, AppendMode.OVERWRITE, true).use { stream ->
                stream.transform(view.toPage)
                stream.transform(if (operation.horizontal) Matrix(-1f, 0f, 0f, 1f, view.width, 0f) else Matrix(1f, 0f, 0f, -1f, 0f, view.height))
                stream.drawForm(original)
            }
        }
    }

    private fun pageForm(document: PDDocument, page: PDPage, check: () -> Unit): PDFormXObject {
        check()
        val form = PDFormXObject(document)
        form.resources = page.resources
        form.bBox = page.cropBox
        page.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.GROUP)?.let {
            form.cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.GROUP, it)
        }
        form.setMatrix(geometry(page).fromPage.createAffineTransform())
        page.contents.use { input ->
            form.contentStream.createOutputStream(com.tom_roush.pdfbox.cos.COSName.FLATE_DECODE).use { output ->
                val buffer = ByteArray(8192)
                var bytes = 0L
                while (true) {
                    check()
                    val length = input.read(buffer)
                    if (length < 0) break
                    bytes += length
                    require(bytes <= 64L * 1024 * 1024) { "A PDF page has too much expanded content" }
                    output.write(buffer, 0, length)
                }
            }
        }
        return form
    }

    internal fun flattenVisibleAnnotations(document: PDDocument, check: () -> Unit) {
        flattenForms(document, check)
        var annotationCount = 0
        for (page in document.pages) {
            check()
            for (annotation in page.annotations.toList()) {
                check()
                require(++annotationCount <= 10_000) { "The document contains too many annotations" }
                if (annotation.isHidden || annotation.isInvisible || annotation.isNoView) continue
                if (annotation.normalAppearanceStream == null) annotation.constructAppearances(document)
                val appearance = annotation.normalAppearanceStream
                if (appearance == null) {
                    require(annotation.subtype == "Link" || annotation.subtype == "Popup") { "An annotation has no appearance and cannot be flattened safely" }
                    continue
                }
                val rect = annotation.rectangle ?: continue
                val box = appearance.bBox ?: continue
                val matrix = appearance.matrix ?: Matrix()
                val points = listOf(matrix.transformPoint(box.lowerLeftX, box.lowerLeftY), matrix.transformPoint(box.lowerLeftX, box.upperRightY), matrix.transformPoint(box.upperRightX, box.lowerLeftY), matrix.transformPoint(box.upperRightX, box.upperRightY))
                val left = points.minOf { it.x }; val bottom = points.minOf { it.y }
                val width = points.maxOf { it.x } - left; val height = points.maxOf { it.y } - bottom
                if (width <= 0 || height <= 0 || rect.width <= 0 || rect.height <= 0) continue
                val sx = rect.width / width; val sy = rect.height / height
                PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { stream ->
                    stream.saveGraphicsState()
                    stream.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - left * sx, rect.lowerLeftY - bottom * sy))
                    stream.drawForm(appearance)
                    stream.restoreGraphicsState()
                }
            }
            page.annotations = emptyList()
        }
    }

    private fun replacePages(document: PDDocument, replacement: List<PDPage>) {
        require(replacement.isNotEmpty() && replacement.size <= MAX_PAGES) { "The output must contain 1 to $MAX_PAGES pages" }
        while (document.numberOfPages > 0) document.removePage(0)
        replacement.forEach(document::addPage)
        document.documentCatalog.acroForm = null
        document.documentCatalog.documentOutline = null
        document.documentCatalog.pageLabels = null
        document.documentCatalog.openAction = null
        // Structure/destination trees reference the replaced pages. Keeping them creates stale links.
        document.documentCatalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.STRUCT_TREE_ROOT)
        document.documentCatalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.DESTS)
        document.documentCatalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.NAMES)
        // Article-thread beads can keep removed pages reachable even after replacing the page tree.
        document.documentCatalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Threads"))
        document.documentCatalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Perms"))
    }

    private data class Geometry(val width: Float, val height: Float, val toPage: Matrix, val fromPage: Matrix)

    /** Display bottom-left coordinates, in physical points, including crop origins and page rotation. */
    private fun geometry(page: PDPage): Geometry {
        val box = page.cropBox
        val unit = page.userUnit
        require(unit.isFinite() && unit > 0 && box.width.isFinite() && box.height.isFinite() && box.width > 0 && box.height > 0) { "Invalid PDF page dimensions" }
        val x = box.lowerLeftX; val y = box.lowerLeftY; val w = box.width; val h = box.height
        val inverse = 1f / unit
        val rotation = ((page.rotation % 360) + 360) % 360
        require(rotation % 90 == 0) { "Unsupported PDF page rotation" }
        val toPage = when (rotation) {
            90 -> Matrix(0f, inverse, -inverse, 0f, x + w, y)
            180 -> Matrix(-inverse, 0f, 0f, -inverse, x + w, y + h)
            270 -> Matrix(0f, -inverse, inverse, 0f, x, y + h)
            else -> Matrix(inverse, 0f, 0f, inverse, x, y)
        }
        val fromPage = when (rotation) {
            90 -> Matrix(0f, -unit, unit, 0f, -y * unit, (x + w) * unit)
            180 -> Matrix(-unit, 0f, 0f, -unit, (x + w) * unit, (y + h) * unit)
            270 -> Matrix(0f, unit, -unit, 0f, (y + h) * unit, -x * unit)
            else -> Matrix(unit, 0f, 0f, unit, -x * unit, -y * unit)
        }
        val width = (if (rotation == 90 || rotation == 270) h else w) * unit
        val height = (if (rotation == 90 || rotation == 270) w else h) * unit
        require(width in .01f..100_000f && height in .01f..100_000f) { "PDF page dimensions exceed the editing limit" }
        return Geometry(width, height, toPage, fromPage)
    }

    private fun textBitmap(text: String, size: Float, color: Int, maxWidth: Int): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 2; this.color = color; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
        val desired = ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt().coerceIn(1, maxWidth)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, desired).setIncludePad(true).build()
        require(desired.toLong() * layout.height <= 4_000_000) { "The inserted text is too large" }
        val bitmap = Bitmap.createBitmap(desired, max(1, layout.height), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap))
        return bitmap
    }

    private fun validateText(text: String) { require(text.length <= MAX_TEXT_CHARACTERS && '\u0000' !in text) { "PDF labels support up to $MAX_TEXT_CHARACTERS characters" } }
    private fun validateFontSize(size: Float) { require(size.isFinite() && size in 4f..100f) { "Font size must be between 4 and 100 points" } }
}
