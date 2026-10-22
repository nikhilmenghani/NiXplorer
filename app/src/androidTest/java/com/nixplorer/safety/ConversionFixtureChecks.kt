package com.nixplorer.safety

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.screen.main.tab.files.conversion.*
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.task.TaskStatus
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Generated app-private fixtures; device owners' files are never used as test inputs. */
class ConversionFixtureChecks(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val results = mutableListOf<String>()
    private val taskIds = mutableListOf<String>()
    private val root = File(context.cacheDir, "conversion-qa-${UUID.randomUUID()}")
    fun run() {
        var resultCode = Activity.RESULT_OK
        var report: String
        try {
            // Custom instrumentation starts before Application.onCreate on some devices.
            instrumentation.runOnMainSync { check(globalClass === context.applicationContext) }
            PDFBoxResourceLoader.init(context.applicationContext)
            check(root.mkdirs())
            runBlocking {
                // Remove only earlier generated fixture records, including interrupted test runs.
                globalClass.taskManager.executionMutex.withLock {
                    val prefix = context.cacheDir.absolutePath + "/conversion-qa-"
                    globalClass.taskManager.journal.load().filter { record ->
                        record.sources.isNotEmpty() && record.sources.all { it.startsWith(prefix) }
                    }.forEach { record ->
                        globalClass.taskManager.removeTask(record.id)
                        globalClass.taskManager.journal.remove(record.id)
                    }
                }
                checkCase("image encoding, dimensions, JPEG alpha flattening") { images() }
                checkCase("document PDF pagination and Unicode DOCX round-trip") { documents() }
                checkCase("batch collision handling, partial failure, and retry") { batchAndRetry() }
                checkCase("batch cancellation retains originals and cleans staging") { cancellation() }
                checkCase("queued conversion accepts a destination chosen later") { deferredDestination() }
                checkCase("MP3 to AAC M4A and PCM WAV") { audio() }
                checkCase("MKV to MP4, video audio extraction") { video() }
            }
            report = results.joinToString("\n", postfix = "\nAll ${results.size} conversion fixture checks passed\n")
        } catch (error: Throwable) {
            resultCode = Activity.RESULT_CANCELED
            report = results.joinToString("\n") + "\nFAILED: ${error.stackTraceToString()}"
        } finally {
            taskIds.forEach { globalClass.taskManager.journal.remove(it) }
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
        // finish() can terminate the instrumented process immediately; cleanup must happen first.
        instrumentation.finish(resultCode, Bundle().apply { putString("stream", report) })
    }
    private suspend fun checkCase(name: String, block: suspend () -> Unit) {
        withTimeout(120_000) { block() }; results += "PASS $name"
    }
    private fun folder(name: String) = File(root, name).apply { check(mkdirs()) }
    private fun png(file: File) {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.setPixel(32, 24, Color.BLUE)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private suspend fun task(files: List<File>, format: ConversionFormat, destination: File): ConversionTask {
        val task = ConversionTask(files.map(::LocalFileHolder), format)
        taskIds += task.id
        task.run(ConversionTaskParameters(destination.absolutePath))
        return task
    }
    private suspend fun images() {
        val source = File(folder("images"), "transparent.png").also(::png)
        val original = source.readBytes()
        for (format in listOf(ConversionFormat.JPEG, ConversionFormat.WEBP)) {
            val out = folder("image-${format.name}")
            val task = task(listOf(source), format, out)
            check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.summary }
            val output = out.listFiles()!!.single()
            check(output.extension == format.extension)
            val decoded = requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            check(decoded.width == 64 && decoded.height == 48)
            if (format == ConversionFormat.JPEG) check(Color.red(decoded.getPixel(0, 0)) > 240)
            decoded.recycle()
        }
        check(source.readBytes().contentEquals(original))
        val corrupt = File(source.parentFile, "corrupt.png").apply { writeText("Not an image") }
        val mixedOut = folder("image-corrupt")
        val mixed = task(listOf(source, corrupt), ConversionFormat.JPEG, mixedOut)
        check(mixed.getCurrentStatus() == TaskStatus.PARTIAL_FAILURE) { mixed.progressMonitor.summary }
        check(mixedOut.listFiles()!!.size == 1 && mixedOut.listFiles()!!.none { it.name.startsWith(".nix-") })
    }
    private suspend fun documents() {
        val dir = folder("documents")
        val source = File(dir, "notes.txt").apply {
            writeText("NiXplorer document sample\nCafé résumé — Ω 中文\n" + (1..180).joinToString("\n") { "Paragraph $it: conversion retains the source text." })
        }
        val docx = File(dir, "notes.docx")
        DocumentConverter.convert(context, source, docx, ConversionFormat.DOCX, { false })
        ZipFile(docx).use { zip -> check(zip.getEntry("[Content_Types].xml") != null); check(zip.getEntry("word/document.xml") != null) }
        val text = File(dir, "roundtrip.txt")
        DocumentConverter.convert(context, docx, text, ConversionFormat.TXT, { false })
        check(text.readText().contains("Café résumé — Ω 中文"))
        check(text.readText().contains("Paragraph 180"))
        val html = File(dir, "notes.html")
        DocumentConverter.convert(context, source, html, ConversionFormat.HTML, { false })
        check(html.readText().contains("charset", ignoreCase = true))
        val pdf = File(dir, "notes.pdf")
        DocumentConverter.convert(context, source, pdf, ConversionFormat.PDF, { false })
        PDDocument.load(pdf).use { document ->
            check(document.numberOfPages > 1)
            check(PDFTextStripper().getText(document).contains("Paragraph 180"))
        }
        val pdfText = File(dir, "pdf-text.txt")
        DocumentConverter.convert(context, pdf, pdfText, ConversionFormat.TXT, { false })
        check(pdfText.readText().contains("NiXplorer"))
        val empty = File(dir, "empty.txt").apply { writeText("") }
        val emptyOut = folder("empty-text-output")
        val emptyTask = task(listOf(empty), ConversionFormat.TXT, emptyOut)
        check(emptyTask.getCurrentStatus() == TaskStatus.SUCCESS) { emptyTask.progressMonitor.summary }
        check(File(emptyOut, "empty.txt").isFile && File(emptyOut, "empty.txt").length() == 0L)
    }
    private suspend fun batchAndRetry() {
        val a = File(folder("batch-a"), "same.txt").apply { writeText("First source") }
        val b = File(folder("batch-b"), "same.txt").apply { writeText("Second source") }
        val broken = File(folder("batch-broken"), "broken.txt")
        val out = folder("batch-out")
        val existing = File(out, "same.html").apply { writeText("Keep existing output") }
        val task = task(listOf(a, b, broken), ConversionFormat.HTML, out)
        check(task.getCurrentStatus() == TaskStatus.PARTIAL_FAILURE) { task.progressMonitor.summary }
        check(existing.readText() == "Keep existing output")
        check(out.listFiles()!!.count { it.extension == "html" } == 3)
        val successfulOutputs = out.listFiles()!!.associate { it.name to it.readBytes() }
        broken.writeText("Retry source")
        task.continueTask()
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.summary }
        check(out.listFiles()!!.count { it.extension == "html" } == 4)
        successfulOutputs.forEach { (name, bytes) -> check(File(out, name).readBytes().contentEquals(bytes)) }
        check(a.readText() == "First source" && b.readText() == "Second source")
        check(out.listFiles()!!.none { it.name.startsWith(".nix-") })
    }
    private suspend fun cancellation() {
        val dir = folder("cancel")
        val source = File(dir, "original.txt").apply { writeText("Keep original") }
        val out = folder("cancel-out")
        val task = ConversionTask(listOf(LocalFileHolder(source)), ConversionFormat.PDF)
        taskIds += task.id
        task.abortTask()
        task.run(ConversionTaskParameters(out.absolutePath))
        check(task.getCurrentStatus() == TaskStatus.CANCELLED) { task.progressMonitor.summary }
        check(source.readText() == "Keep original")
        check(out.listFiles()!!.isEmpty())
        task.aborted = false
        task.continueTask()
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.summary }
    }
    private suspend fun deferredDestination() {
        val sourceDir = folder("queued-source")
        val source = File(sourceDir, "queued.txt").apply { writeText("Choose the destination later") }
        val task = ConversionTask(listOf(LocalFileHolder(source)), ConversionFormat.HTML)
        taskIds += task.id
        check(task.getCurrentStatus() == TaskStatus.PENDING)
        check(task.validate()) // Pending conversions do not need a destination yet.
        val destination = folder("queued-destination")
        task.setParameters(ConversionTaskParameters(destination.absolutePath))
        task.run()
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.summary }
        check(File(destination, "queued.html").readText().contains("Choose the destination later"))
        check(sourceDir.listFiles()!!.size == 1 && source.readText() == "Choose the destination later")
    }
    private fun asset(name: String, dir: File): File = File(dir, name).apply {
        instrumentation.context.assets.open("conversion/$name").use { input -> outputStream().use { input.copyTo(it) } }
    }
    private fun tracks(file: File): List<String> {
        val extractor = MediaExtractor()
        return try { extractor.setDataSource(file.absolutePath); (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() } }
        finally { extractor.release() }
    }
    private suspend fun audio() {
        val dir = folder("audio")
        val source = asset("sample.mp3", dir)
        val m4a = File(dir, "converted.m4a").apply { check(createNewFile()) }
        MediaConverter.convert(context, source, m4a, ConversionFormat.M4A, { false }, {})
        check(tracks(m4a) == listOf("audio/mp4a-latm")) { tracks(m4a).toString() }
        val wav = File(dir, "converted.wav")
        MediaConverter.convert(context, source, wav, ConversionFormat.WAV, { false }, {})
        val bytes = wav.readBytes()
        check(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE" && bytes.size > 44)
        check(bytes.drop(44).any { it != 0.toByte() })
        check(tracks(wav).any { it == "audio/raw" })
    }
    private suspend fun video() {
        val dir = folder("video")
        val source = asset("sample.mkv", dir)
        val mp4 = File(dir, "converted.mp4").apply { check(createNewFile()) }
        MediaConverter.convert(context, source, mp4, ConversionFormat.MP4, { false }, {})
        check(tracks(mp4).any { it == "video/avc" }) { tracks(mp4).toString() }
        check(tracks(mp4).any { it == "audio/mp4a-latm" })
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4.absolutePath)
            val frame = requireNotNull(retriever.getFrameAtTime(500_000))
            check(frame.width == 320 && frame.height == 180)
            frame.recycle()
        } finally { retriever.release() }
        val m4a = File(dir, "extracted.m4a")
        MediaConverter.convert(context, source, m4a, ConversionFormat.M4A, { false }, {})
        check(tracks(m4a) == listOf("audio/mp4a-latm"))
    }
}
