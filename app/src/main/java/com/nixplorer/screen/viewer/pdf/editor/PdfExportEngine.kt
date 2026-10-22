package com.nixplorer.screen.viewer.pdf.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.sqrt

enum class PdfExportKind { PAGE_PNG, PAGE_JPEG, EMBEDDED_IMAGES, TEXT, SPLIT_PAGES, SPLIT_BOOKMARKS, SPLIT_HALVES }
data class PdfExportResult(val entryCount: Int, val bytes: Long)

/** Export a private staged result before opening the user-selected output. Originals are read-only. */
object PdfExportEngine {
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_PIXELS = 12_000_000L
    private val splitKinds = setOf(PdfExportKind.SPLIT_PAGES, PdfExportKind.SPLIT_BOOKMARKS, PdfExportKind.SPLIT_HALVES)

    suspend fun export(
        context: Context, source: Uri, password: String? = null,
        operations: List<PdfEditOperation> = emptyList(), kind: PdfExportKind, destination: Uri,
        cancelled: () -> Boolean = { false }, onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): PdfExportResult = withContext(Dispatchers.IO) {
        PdfOutputSafety.requireDifferentFile(context, source, destination)
        val activeContext = coroutineContext
        fun checkActive() {
            activeContext.ensureActive()
            if (cancelled()) throw kotlinx.coroutines.CancellationException("PDF export cancelled")
        }
        checkActive()
        val sourceInfo = PdfEditorEngine.inspect(context, source, password, cancelled = cancelled)
        require(sourceInfo.canExtract) { "This PDF does not permit extracting its contents." }
        val staged = File.createTempFile("pdf-export-", ".stage", context.cacheDir)
        try {
            var entries = 0
            PdfEditorEngine.withDocument(context, source, password, operations, cancelled) { working ->
                if (kind in splitKinds) PdfDocumentTools.flattenVisibleAnnotations(working, ::checkActive)
                // Canonical save finalizes font subsets and appearances before copying edited pages.
                withCanonicalDocument(context, working, ::checkActive) { document ->
                    staged.outputStream().use { fileStream ->
                        val limited = LimitedOutput(fileStream, ::checkActive)
                        if (kind == PdfExportKind.TEXT) {
                            val stripper = object : PDFTextStripper() {
                                private var pagePositions = 0
                                private var positions = 0
                                override fun startPage(page: PDPage) { checkActive(); pagePositions = 0; super.startPage(page) }
                                override fun processTextPosition(text: TextPosition) {
                                    checkActive()
                                    require(++pagePositions <= 100_000 && ++positions <= 2_000_000) { "The PDF contains too much text to extract safely." }
                                    super.processTextPosition(text)
                                }
                            }.apply { sortByPosition = true }
                            var characters = 0
                            var hasText = false
                            limited.bufferedWriter(Charsets.UTF_8).use { writer ->
                                val bounded = object : java.io.Writer() {
                                    override fun write(buffer: CharArray, offset: Int, length: Int) {
                                        checkActive()
                                        require(characters.toLong() + length <= 2_000_000) { "Extracted text exceeds the two million character limit." }
                                        characters += length
                                        if (!hasText) hasText = (offset until offset + length).any { !buffer[it].isWhitespace() }
                                        writer.write(buffer, offset, length)
                                    }
                                    override fun flush() = writer.flush()
                                    override fun close() = Unit
                                }
                                for (index in 0 until document.numberOfPages) {
                                    checkActive()
                                    stripper.startPage = index + 1; stripper.endPage = index + 1
                                    stripper.writeText(document, bounded)
                                    onProgress(index + 1, document.numberOfPages)
                                }
                            }
                            require(hasText) { "No embedded text was found. Scanned pages require OCR." }
                            entries = 1
                        } else ZipOutputStream(limited).use { zip ->
                            val renderer by lazy { PDFRenderer(document).apply { isSubsamplingAllowed = true } }
                            val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                            fun entry(name: String, block: (OutputStream) -> Unit) {
                                checkActive()
                                require(entries < 500) { "The PDF contains more than 500 export entries." }
                                zip.putNextEntry(ZipEntry(name)); block(zip); zip.closeEntry(); entries++
                            }
                            fun pdfEntry(name: String, pages: List<Int>, crop: ((PDPage) -> PDRectangle)? = null) {
                                entry(name) { output ->
                                    PDDocument(memory(context)).use { single ->
                                        val cloner = PDFCloneUtility(single)
                                        pages.forEach { index ->
                                            checkActive()
                                            val original = document.getPage(index)
                                            val copied = copyPage(single, original, cloner, ::checkActive)
                                            crop?.let { copied.cropBox = it(original) }
                                            single.addPage(copied)
                                        }
                                        PdfEditorEngine.pruneUnreachable(single)
                                        // PDFBox closes its supplied stream; the containing ZIP stays open.
                                        single.save(LimitedOutput(output, ::checkActive, closeUnderlying = false))
                                    }
                                }
                            }
                            fun extractImages(resources: PDResources?, page: Int, depth: Int) {
                                require(depth <= 16) { "Embedded image nesting is too deep." }
                                if (resources == null || !seen.add(resources.cosObject)) return
                                for (name in resources.xObjectNames) {
                                    checkActive()
                                    when (val item = resources.getXObject(name)) {
                                        is PDImageXObject -> if (seen.add(item.cosObject)) {
                                            require(item.width > 0 && item.height > 0 && item.width.toLong() * item.height <= MAX_PIXELS) {
                                                "An embedded image exceeds 12 megapixels. Export rendered pages instead."
                                            }
                                            val bitmap = item.image
                                            try {
                                                entry("page-${page + 1}-image-${entries + 1}.png") {
                                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "The embedded image could not be encoded." }
                                                }
                                            } finally { bitmap.recycle() }
                                        }
                                        is PDFormXObject -> extractImages(item.resources, page, depth + 1)
                                    }
                                }
                            }
                            if (kind == PdfExportKind.SPLIT_BOOKMARKS) {
                                val sections = bookmarkSections(document, ::checkActive)
                                sections.forEachIndexed { index, section ->
                                    val safeTitle = section.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(80).ifBlank { "section" }
                                    pdfEntry("${(index + 1).toString().padStart(4, '0')}-$safeTitle.pdf", (section.start..section.end).toList())
                                    onProgress(index + 1, sections.size)
                                }
                            } else for (index in 0 until document.numberOfPages) {
                                checkActive()
                                val number = (index + 1).toString().padStart(4, '0')
                                when (kind) {
                                    PdfExportKind.PAGE_PNG, PdfExportKind.PAGE_JPEG -> {
                                        val box = document.getPage(index).cropBox
                                        val area = box.width.toDouble() * box.height
                                        require(area.isFinite() && area > 0) { "Invalid PDF page dimensions." }
                                        val scale = min(2.0, sqrt(MAX_PIXELS / area)).toFloat()
                                        val bitmap = renderer.renderImage(index, scale, ImageType.RGB)
                                        val png = kind == PdfExportKind.PAGE_PNG
                                        try {
                                            entry("page-$number.${if (png) "png" else "jpg"}") {
                                                check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, it))
                                            }
                                        } finally { bitmap.recycle() }
                                    }
                                    PdfExportKind.EMBEDDED_IMAGES -> extractImages(document.getPage(index).resources, index, 0)
                                    PdfExportKind.SPLIT_PAGES -> pdfEntry("page-$number.pdf", listOf(index))
                                    PdfExportKind.SPLIT_HALVES -> {
                                        pdfEntry("page-$number-top.pdf", listOf(index)) { halfCrop(it, true) }
                                        pdfEntry("page-$number-bottom.pdf", listOf(index)) { halfCrop(it, false) }
                                    }
                                    PdfExportKind.TEXT, PdfExportKind.SPLIT_BOOKMARKS -> error("Handled above")
                                }
                                onProgress(index + 1, document.numberOfPages)
                            }
                            require(entries > 0) { "This PDF has no embedded images to extract." }
                        }
                    }
                }
            }
            checkActive()
            require(staged.length() > 0)
            // Verify the exact opened descriptors before truncation and retain the checked handle.
            context.contentResolver.openFileDescriptor(source, "r").use { original ->
                requireNotNull(original) { "Cannot verify the original file identity." }
                val inputStat = Os.fstat(original.fileDescriptor)
                val target = requireNotNull(context.contentResolver.openFileDescriptor(destination, "rw")) { "Cannot open the selected output file." }
                try {
                    val targetStat = Os.fstat(target.fileDescriptor)
                    require(inputStat.st_dev != targetStat.st_dev || inputStat.st_ino != targetStat.st_ino) { "The output points to the original file. Choose a new name." }
                    ParcelFileDescriptor.AutoCloseOutputStream(target).use { output ->
                        output.channel.truncate(0); output.channel.position(0)
                        staged.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                        output.flush(); output.fd.sync()
                    }
                } finally { runCatching { target.close() } }
            }
            PdfExportResult(entries, staged.length())
        } finally { staged.delete() }
    }

    private fun <T> withCanonicalDocument(context: Context, source: PDDocument, check: () -> Unit, block: (PDDocument) -> T): T {
        val file = File.createTempFile("pdf-export-working-", ".pdf", context.cacheDir)
        try {
            check()
            // Extracted images/text and split documents are separate assets, not protected working PDFs.
            // This unencrypted copy stays private and is deleted; the original permission check remains.
            source.isAllSecurityToBeRemoved = true
            PdfEditorEngine.pruneUnreachable(source)
            file.outputStream().use { source.save(LimitedOutput(it, check)) }
            check()
            return PDDocument.load(file, memory(context)).use(block)
        } finally { file.delete() }
    }

    /** Copy page painting data with explicit inherited resources and no foreign annotation/page links. */
    private fun copyPage(target: PDDocument, source: PDPage, cloner: PDFCloneUtility, check: () -> Unit): PDPage {
        val page = PDPage(PDRectangle(source.mediaBox.cosArray))
        page.cropBox = PDRectangle(source.cropBox.cosArray)
        page.bleedBox = PDRectangle(source.bleedBox.cosArray)
        page.trimBox = PDRectangle(source.trimBox.cosArray)
        page.artBox = PDRectangle(source.artBox.cosArray)
        page.rotation = source.rotation; page.userUnit = source.userUnit
        source.resources?.let { page.resources = PDResources(cloner.cloneForNewDocument(it.cosObject) as COSDictionary) }
        source.cosObject.getDictionaryObject(COSName.GROUP)?.let { page.cosObject.setItem(COSName.GROUP, cloner.cloneForNewDocument(it)) }
        val stream = PDStream(target)
        source.contents.use { input ->
            stream.createOutputStream(COSName.FLATE_DECODE).use { output ->
                val buffer = ByteArray(8192)
                var count = 0L
                while (true) {
                    check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= 64L * 1024 * 1024) { "A page has too much expanded content to split." }
                    output.write(buffer, 0, read)
                }
            }
        }
        page.setContents(stream)
        return page
    }

    private data class Section(val title: String, val start: Int, val end: Int)

    private fun bookmarkSections(document: PDDocument, check: () -> Unit): List<Section> {
        var item: PDOutlineItem? = document.documentCatalog.documentOutline?.firstChild
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val starts = ArrayList<Pair<String, Int>>()
        while (item != null) {
            check()
            require(seen.add(item.cosObject) && seen.size <= 500) { "The bookmark tree is invalid or exceeds 500 top-level entries." }
            val destination = item.findDestinationPage(document)
            if (destination != null) {
                val index = (0 until document.numberOfPages).firstOrNull { document.getPage(it).cosObject === destination.cosObject }
                if (index != null) starts += item.title.orEmpty() to index
            }
            item = item.nextSibling
        }
        val ordered = starts.sortedBy { it.second }.distinctBy { it.second }
        require(ordered.isNotEmpty()) { "This PDF has no top-level bookmarks that point to its pages." }
        val sections = ArrayList<Section>()
        if (ordered.first().second > 0) sections += Section("preface", 0, ordered.first().second - 1)
        ordered.forEachIndexed { index, entry -> sections += Section(entry.first, entry.second, (ordered.getOrNull(index + 1)?.second ?: document.numberOfPages) - 1) }
        return sections
    }

    /** Outside content remains in cropped halves. This layout tool does not redact content. */
    private fun halfCrop(page: PDPage, top: Boolean): PDRectangle {
        val box = page.cropBox
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> PDRectangle(box.lowerLeftX + (if (top) 0f else box.width / 2), box.lowerLeftY, box.width / 2, box.height)
            180 -> PDRectangle(box.lowerLeftX, box.lowerLeftY + (if (top) 0f else box.height / 2), box.width, box.height / 2)
            270 -> PDRectangle(box.lowerLeftX + (if (top) box.width / 2 else 0f), box.lowerLeftY, box.width / 2, box.height)
            else -> PDRectangle(box.lowerLeftX, box.lowerLeftY + (if (top) box.height / 2 else 0f), box.width, box.height / 2)
        }
    }

    private fun memory(context: Context) = MemoryUsageSetting.setupMixed(4L * 1024 * 1024, 128L * 1024 * 1024).setTempDir(context.cacheDir)

    private class LimitedOutput(output: OutputStream, private val check: () -> Unit, private val closeUnderlying: Boolean = true) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) { reserve(1); out.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); out.write(bytes, offset, length) }
        override fun close() { if (closeUnderlying) super.close() else flush() }
        private fun reserve(length: Int) {
            check()
            require(count + length <= MAX_BYTES) { "Export exceeds the 256 MiB output limit. Export fewer pages." }
            count += length
        }
    }
}
