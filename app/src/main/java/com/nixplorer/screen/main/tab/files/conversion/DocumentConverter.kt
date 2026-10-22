package com.nixplorer.screen.main.tab.files.conversion

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Text-oriented document conversion. Layout, images, formulas and office styling are not retained. */
internal object DocumentConverter {
    private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 12 * 1024 * 1024
    private const val MAX_CHARACTERS = 2_000_000
    private const val MAX_PAGES = 500
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val ODT_TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"

    suspend fun convert(
        context: Context,
        source: File,
        target: File,
        format: ConversionFormat,
        cancelled: () -> Boolean
    ) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val checkCancelled = {
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Conversion cancelled")
        }
        checkCancelled()
        require(source.isFile && source.canRead()) { "Cannot read ${source.name}" }
        require(source.length() <= MAX_SOURCE_BYTES) { "Document exceeds the 64 MiB conversion limit" }
        require(source.canonicalFile != target.canonicalFile) { "Conversion must create a new file" }
        val extension = source.extension.lowercase(Locale.ROOT)
        require(extension != "pdf" || format == ConversionFormat.TXT) { "PDF supports text extraction to TXT" }
        val text = when (extension) {
            "pdf" -> readPdf(context, source, checkCancelled)
            "docx", "odt" -> readOffice(source, extension == "docx", checkCancelled)
            "html", "htm" -> {
                val html = decodeText(source.inputStream(), checkCancelled)
                    .replace(Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"), "")
                    .replace(Regex("(?is)<style\\b[^>]*>.*?</style\\s*>"), "")
                Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trimEnd()
            }
            "txt", "md", "markdown", "csv", "log" -> decodeText(source.inputStream(), checkCancelled)
            else -> error("This document format is not supported")
        }.replace("\r\n", "\n").replace('\r', '\n')
        require(text.length <= MAX_CHARACTERS) { "Document exceeds the 2 million character conversion limit" }
        checkCancelled()
        when (format) {
            ConversionFormat.TXT -> target.writeText(text, Charsets.UTF_8)
            ConversionFormat.HTML -> target.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("<!doctype html><html><head><meta charset=\"utf-8\"><title>")
                writer.write(escapeXml(source.nameWithoutExtension))
                writer.write("</title></head><body><pre style=\"white-space:pre-wrap;font-family:sans-serif\">")
                for (part in text.chunked(8192)) { checkCancelled(); writer.write(escapeXml(part)) }
                writer.write("</pre></body></html>")
            }
            ConversionFormat.DOCX -> writeDocx(target, text, checkCancelled)
            ConversionFormat.PDF -> writePdf(target, text, checkCancelled)
            else -> error("Unsupported document output format")
        }
        checkCancelled()
    }

    private fun readBounded(input: InputStream, checkCancelled: () -> Unit): ByteArray = input.use {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = it.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= MAX_TEXT_BYTES) { "Document text exceeds the 12 MiB conversion limit" }
            bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
    }

    private fun decodeText(input: InputStream, checkCancelled: () -> Unit): String {
        val bytes = readBounded(input, checkCancelled)
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val result = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Text must use UTF-8 or UTF-16 encoding", error)
        }
        require('\u0000' !in result) { "The file contains binary data rather than supported text" }
        require(result.length <= MAX_CHARACTERS) { "Document exceeds the 2 million character conversion limit" }
        return result
    }

    private fun readOffice(source: File, word: Boolean, checkCancelled: () -> Unit): String = ZipFile(source).use { zip ->
        require(zip.size() <= 10_000) { "Office document contains too many package entries" }
        val name = if (word) "word/document.xml" else "content.xml"
        val entry = requireNotNull(zip.getEntry(name)) { "Document text is missing or the office file is encrypted" }
        require(entry.size <= MAX_TEXT_BYTES) { "Office document text exceeds the 12 MiB conversion limit" }
        val bytes = readBounded(zip.getInputStream(entry), checkCancelled)
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(bytes.inputStream(), null)
        }
        val result = StringBuilder()
        var textDepth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            checkCancelled()
            require(event != XmlPullParser.DOCDECL) { "XML document type declarations are not supported" }
            val namespace = parser.namespace.orEmpty()
            val nameAtEvent = parser.name
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (word && namespace == WORD_NS) {
                        when (nameAtEvent) {
                            "t" -> textDepth++
                            "tab" -> result.append('\t')
                            "br", "cr" -> result.append('\n')
                        }
                    } else if (!word && namespace == ODT_TEXT_NS) {
                        when (nameAtEvent) {
                            "p", "h" -> textDepth++
                            "tab" -> result.append('\t')
                            "line-break" -> result.append('\n')
                            "s" -> {
                                val spaces = parser.getAttributeValue(ODT_TEXT_NS, "c")?.toIntOrNull() ?: 1
                                require(spaces in 1..10_000) { "Invalid office document spacing" }
                                result.append(" ".repeat(spaces))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> if (textDepth > 0) {
                    require(event != XmlPullParser.ENTITY_REF || parser.text != null) { "Unresolved XML entity" }
                    result.append(parser.text.orEmpty())
                }
                XmlPullParser.END_TAG -> {
                    if (word && namespace == WORD_NS) {
                        when (nameAtEvent) {
                            "t" -> textDepth--
                            "p", "tr" -> result.append('\n')
                            "tc" -> result.append('\t')
                        }
                    } else if (!word && namespace == ODT_TEXT_NS && nameAtEvent in setOf("p", "h")) {
                        textDepth--
                        result.append('\n')
                    }
                }
            }
            require(result.length <= MAX_CHARACTERS) { "Document exceeds the 2 million character conversion limit" }
            event = parser.nextToken()
        }
        result.toString().trimEnd()
    }

    private fun readPdf(context: Context, source: File, checkCancelled: () -> Unit): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val memory = MemoryUsageSetting.setupMixed(4L * 1024 * 1024, 64L * 1024 * 1024).setTempDir(context.cacheDir)
        return PDDocument.load(source, memory).use { document ->
            require(!document.isEncrypted && document.currentAccessPermission.canExtractContent()) { "This PDF does not allow text extraction" }
            require(document.numberOfPages <= MAX_PAGES) { "PDF exceeds the 500 page conversion limit" }
            val result = StringBuilder()
            val stripper = object : PDFTextStripper() {
                private var positions = 0
                private var pagePositions = 0
                override fun startPage(page: PDPage) {
                    checkCancelled()
                    pagePositions = 0
                    super.startPage(page)
                }
                override fun processTextPosition(text: TextPosition) {
                    checkCancelled()
                    // PDFTextStripper accumulates a page's positions before calling the writer.
                    require(++pagePositions <= 100_000) { "A PDF page contains too much text to convert safely" }
                    require(++positions <= MAX_CHARACTERS) { "PDF exceeds the 2 million character conversion limit" }
                    super.processTextPosition(text)
                }
            }.apply { sortByPosition = true }
            stripper.writeText(document, object : Writer() {
                override fun write(buffer: CharArray, offset: Int, count: Int) {
                    checkCancelled()
                    require(result.length.toLong() + count <= MAX_CHARACTERS) { "PDF exceeds the 2 million character conversion limit" }
                    result.append(buffer, offset, count)
                }
                override fun flush() = Unit
                override fun close() = Unit
            })
            require(result.isNotBlank()) { "This PDF has no extractable text. Scanned PDFs need OCR, which is not supported" }
            result.toString()
        }
    }

    private fun writeDocx(target: File, text: String, checkCancelled: () -> Unit) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun entry(name: String, xml: String) {
                checkCancelled()
                zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            zip.putNextEntry(ZipEntry("word/document.xml"))
            // Do not close this writer independently: the ZIP still needs its central directory.
            val writer = zip.writer(Charsets.UTF_8)
            writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$WORD_NS"><w:body>""")
            for (paragraph in text.lineSequence()) {
                checkCancelled()
                writer.write("<w:p><w:r><w:t xml:space=\"preserve\">")
                writer.write(escapeXml(paragraph).replace("\t", "</w:t><w:tab/><w:t xml:space=\"preserve\">"))
                writer.write("</w:t></w:r></w:p>")
            }
            writer.write("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"880\" w:right=\"880\" w:bottom=\"880\" w:left=\"880\"/></w:sectPr></w:body></w:document>")
            writer.flush()
            zip.closeEntry()
        }
    }

    private fun writePdf(target: File, text: String, checkCancelled: () -> Unit) {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 44
        val contentHeight = pageHeight - margin * 2
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 12f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, pageWidth - margin * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1f).build()
        val document = PdfDocument()
        try {
            var firstLine = 0
            var pageNumber = 1
            do {
                checkCancelled()
                require(pageNumber <= MAX_PAGES) { "Converted PDF would exceed 500 pages" }
                val top = layout.getLineTop(firstLine)
                var endLine = firstLine + 1
                while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= contentHeight) endLine++
                val bottom = layout.getLineTop(endLine)
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create())
                try {
                    page.canvas.save()
                    page.canvas.clipRect(margin, margin, pageWidth - margin, margin + minOf(bottom - top, contentHeight))
                    page.canvas.translate(margin.toFloat(), (margin - top).toFloat())
                    layout.draw(page.canvas)
                    page.canvas.restore()
                } finally { document.finishPage(page) }
                firstLine = endLine
            } while (firstLine < layout.lineCount)
            checkCancelled()
            target.outputStream().buffered().use(document::writeTo)
        } finally { document.close() }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (character >= ' ' || character == '\t' || character == '\n' || character == '\r') append(character)
            }
        }
    }
}
