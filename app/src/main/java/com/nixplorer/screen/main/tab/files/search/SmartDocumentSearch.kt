package com.nixplorer.screen.main.tab.files.search

import android.content.Context
import com.nixplorer.screen.main.tab.files.misc.FileMimeType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class SemanticMatch(val score: Float, val snippet: String)
internal object SmartDocumentSearch {
    private val textTypes = (FileMimeType.editableFileType + FileMimeType.codeFileType).toSet()
    private const val MAX_CHARACTERS = 5600
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    fun supports(file: File) = file.extension.lowercase() in textTypes || file.extension.lowercase() in setOf("pdf", "docx")

    suspend fun match(context: Context, file: File, query: FloatArray, requiredTerms: List<String> = emptyList()): SemanticMatch? = withContext(Dispatchers.IO) {
        require(file.isFile && file.canRead()) { "Cannot read ${file.name}" }
        require(file.length() <= MAX_FILE_BYTES) { "Content skipped for ${file.name}: exceeds 20 MiB" }
        var documentText: String? = null
        if (requiredTerms.isNotEmpty()) {
            documentText = readText(context, file)
            if (!SearchRelevance.containsRequired(file.name + " " + documentText, requiredTerms)) return@withContext null
        }
        val stamp = "v2|${file.canonicalPath}|${file.length()}|${file.lastModified()}"
        val key = MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "smart-search-vectors").apply { check(isDirectory || mkdirs()) }
        val cache = File(directory, key)
        val cached = runCatching {
            DataInputStream(cache.inputStream().buffered()).use { input ->
                val count = input.readInt(); require(count in 0..8)
                List(count) { input.readUTF() to FloatArray(384) { input.readFloat() } }
            }
        }.getOrNull()
        val chunks = cached ?: run {
            val text = documentText ?: readText(context, file)
            val parts = text.chunked(700).take(8)
            val vectors = parts.map { part ->
                currentCoroutineContext().ensureActive()
                part.take(180).replace(Regex("\\s+"), " ") to SmartSearchModel.embed(context, file.name + ". " + part)
            }
            require(stamp == "v2|${file.canonicalPath}|${file.length()}|${file.lastModified()}") { "File changed while indexing ${file.name}" }
            val stage = File.createTempFile("index-", ".part", directory)
            try {
                DataOutputStream(stage.outputStream().buffered()).use { out ->
                    out.writeInt(vectors.size)
                    vectors.forEach { (snippet, vector) -> out.writeUTF(snippet); vector.forEach(out::writeFloat) }
                }
                java.nio.file.Files.move(stage.toPath(), cache.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { stage.delete() }
            // Cache is disposable and bounded; no user documents are changed.
            val entries = directory.listFiles().orEmpty()
            if (entries.size > 2000) entries.sortedBy { it.lastModified() }.take(entries.size - 1800).forEach { it.delete() }
            vectors
        }
        currentCoroutineContext().ensureActive()
        val best = chunks.map { (snippet, vector) -> SemanticMatch(SmartSearchModel.similarity(query, vector), snippet) }
            .maxByOrNull { it.score }
        if (requiredTerms.isNotEmpty() && best != null) {
            val text = documentText.orEmpty()
            val position = text.indexOf(requiredTerms.first(), ignoreCase = true).coerceAtLeast(0)
            val snippet = text.drop((position - 40).coerceAtLeast(0)).take(180).replace(Regex("\\s+"), " ")
            SemanticMatch(0.65f + 0.35f * best.score.coerceIn(0f, 1f), snippet)
        } else best?.takeIf { it.score >= 0.35f }
    }

    @androidx.annotation.RequiresApi(35)
    private suspend fun readPlatformPdf(file: File): String {
        val result = StringBuilder()
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { document ->
                for (index in 0 until minOf(document.pageCount, 5)) {
                    currentCoroutineContext().ensureActive()
                    document.openPage(index).use { page ->
                        for (block in page.textContents) {
                            currentCoroutineContext().ensureActive()
                            val remaining = MAX_CHARACTERS - result.length
                            if (remaining <= 0) break
                            result.append(block.text.take(remaining))
                            if (result.length < MAX_CHARACTERS) result.append(' ')
                        }
                    }
                    if (result.length >= MAX_CHARACTERS) break
                }
            }
        }
        return result.toString()
    }

    internal suspend fun readText(context: Context, file: File): String {
        currentCoroutineContext().ensureActive()
        return when (file.extension.lowercase()) {
            "pdf" -> {
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    try {
                        val nativeText = readPlatformPdf(file)
                        if (nativeText.isNotBlank()) return nativeText
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Older PDFBox reader remains an alternate parser. */ }
                }
                PDFBoxResourceLoader.init(context.applicationContext)
                PDDocument.load(file, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(4L * 1024 * 1024, 32L * 1024 * 1024)).use { document ->
                    require(!document.isEncrypted) { "Encrypted PDF content is not indexed" }
                    val job = currentCoroutineContext().job
                    val result = StringBuilder()
                    val stripper = object : PDFTextStripper() {
                        var characters = 0
                        override fun processTextPosition(text: com.tom_roush.pdfbox.text.TextPosition) {
                            job.ensureActive()
                            if (characters++ < MAX_CHARACTERS) super.processTextPosition(text)
                        }
                    }
                    for (page in 1..minOf(document.numberOfPages, 5)) {
                        currentCoroutineContext().ensureActive()
                        stripper.startPage = page; stripper.endPage = page
                        // Bounded output even when a single PDF page contains enormous text.
                        stripper.writeText(document, object : Writer() {
                            override fun write(buffer: CharArray, offset: Int, count: Int) {
                                job.ensureActive()
                                val remaining = MAX_CHARACTERS - result.length
                                if (remaining > 0) result.append(buffer, offset, minOf(count, remaining))
                            }
                            override fun flush() {}
                            override fun close() {}
                        })
                        if (result.length >= MAX_CHARACTERS) break
                    }
                    result.toString()
                }
            }
            "docx" -> ZipFile(file).use { zip ->
                val entry = requireNotNull(zip.getEntry("word/document.xml")) { "DOCX text is unavailable" }
                zip.getInputStream(entry).use { raw ->
                    val bounded = object : FilterInputStream(raw) {
                        var bytes = 0
                        override fun read(): Int { require(++bytes <= 2 * 1024 * 1024) { "DOCX text limit reached" }; return super.read() }
                        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(buffer, off, minOf(len, 2 * 1024 * 1024 - bytes + 1))
                            if (n > 0) bytes += n
                            require(bytes <= 2 * 1024 * 1024) { "DOCX text limit reached" }; return n
                        }
                    }
                    val parser = android.util.Xml.newPullParser().apply {
                        setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                        setInput(bounded, "UTF-8")
                    }
                    val result = StringBuilder()
                    while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && result.length < MAX_CHARACTERS) {
                        currentCoroutineContext().ensureActive()
                        require(parser.eventType != org.xmlpull.v1.XmlPullParser.DOCDECL) { "DOCX entity declarations are unsupported" }
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.TEXT) result.append(parser.text.take(MAX_CHARACTERS - result.length))
                        if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "p") result.append(' ')
                        parser.next()
                    }
                    result.take(MAX_CHARACTERS).toString()
                }
            }
            else -> file.reader().use { reader ->
                val buffer = CharArray(MAX_CHARACTERS); var used = 0
                while (used < buffer.size) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer, used, buffer.size - used); if (count < 0) break; used += count
                }
                String(buffer, 0, used).also { require('\u0000' !in it) { "Binary content skipped" } }
            }
        }
    }
}
