package com.nixplorer.safety

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import com.nixplorer.screen.viewer.pdf.editor.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Disposable app-private PDF fixtures; includes logical form and forensic redaction checks. */
class PdfEditorFixtureChecks(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private val root = File(context.cacheDir, "pdf-fixture-${UUID.randomUUID()}")
    private val results = mutableListOf<String>()
    private lateinit var source: File
    private val rect = PdfRect(.1f, .3f, .7f, .4f)

    fun run() {
        var code = Activity.RESULT_OK
        var report: String
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            check(root.mkdirs())
            source = File(root, "source.pdf")
            instrumentation.context.assets.open("pdf-editing-sample.pdf").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val originalHash = hash(source)
            runBlocking {
                checkCase("inspect forms, bookmarks and metadata") {
                    val info = PdfEditorEngine.inspect(context, Uri.fromFile(source))
                    check(info.pageCount == 3)
                    check(info.fields.map { it.name }.containsAll(listOf("reviewer", "reviewed")))
                    check(info.metadata["Title"].orEmpty().contains("editing sample", true))
                }
                checkCase("text, drawing, shapes, markup and hyperlinks survive save and render") {
                    val out = export("annotations", listOf(
                        PdfEditOperation.Text(0, rect, "Added in NiXplorer", 18f),
                        PdfEditOperation.Ink(0, listOf(PdfPoint(.1f, .6f), PdfPoint(.3f, .65f))),
                        PdfEditOperation.Shape(0, PdfRect(.1f, .7f, .5f, .8f), PdfShapeKind.RECTANGLE),
                        PdfEditOperation.Markup(0, PdfRect(.1f, .25f, .9f, .28f), PdfMarkupKind.HIGHLIGHT),
                        PdfEditOperation.Link(0, PdfRect(.1f, .85f, .4f, .9f), "https://example.com")
                    ))
                    PDDocument.load(out).use { pdf ->
                        check(PDFTextStripper().getText(pdf).contains("Added in NiXplorer"))
                        check(pdf.getPage(0).annotations.any { it is PDAnnotationLink })
                    }
                    val preview = PdfEditorEngine.render(context, Uri.fromFile(out), 0, 800)
                    check(preview.width > 0 && preview.height > 0)
                    preview.recycle()
                }
                checkCase("whiteout retains underlying text and is distinct from redaction") {
                    val out = export("whiteout", listOf(PdfEditOperation.Whiteout(0, PdfRect(0f, 0f, 1f, 1f))))
                    PDDocument.load(out).use { check(PDFTextStripper().getText(it).contains("Review and annotate")) }
                }
                checkCase("redaction removes original text streams, forms and hidden source objects") {
                    val out = export("redacted", listOf(PdfEditOperation.Redact(0, PdfRect(0f, 0f, 1f, 1f), "Replacement")))
                    PDDocument.load(out).use { pdf ->
                        val text = PDFTextStripper().getText(pdf)
                        check(!text.contains("Review and annotate"))
                        check(text.contains("Replacement"))
                        pdf.document.objects.forEach { obj ->
                            val stream = obj.`object` as? COSStream ?: return@forEach
                            stream.createInputStream().use { input ->
                                val contents = input.readBytes().toString(Charsets.ISO_8859_1)
                                check(!contents.contains("Review and annotate")) { "Original redacted text remains in a serialized stream" }
                            }
                        }
                    }
                }
                checkCase("page order, duplication, deletion, blank insertion and rotation") {
                    val out = export("organized", listOf(
                        PdfEditOperation.PageOrder(listOf(2, 0, 0)),
                        PdfEditOperation.Rotate(listOf(1), 90),
                        PdfEditOperation.BlankPage(2)
                    ))
                    PDDocument.load(out).use { pdf ->
                        check(pdf.numberOfPages == 4)
                        check(pdf.getPage(1).rotation == 90)
                        check(pdf.getPage(2).rotation == 0) { "Duplicated pages must have independent dictionaries" }
                        val stripper = PDFTextStripper().apply { startPage = 1; endPage = 1 }
                        check(stripper.getText(pdf).contains("Organize and export"))
                    }
                }
                checkCase("form filling updates canonical values and widget appearances") {
                    val out = export("forms", listOf(
                        PdfEditOperation.TextField(0, rect, "newField"),
                        PdfEditOperation.Checkbox(0, PdfRect(.1f, .5f, .14f, .54f), "newCheck"),
                        PdfEditOperation.FillForm(mapOf("reviewer" to "POCO test", "reviewed" to "true", "newField" to "Saved value", "newCheck" to "true"))
                    ))
                    PDDocument.load(out).use { pdf ->
                        val form = requireNotNull(pdf.documentCatalog.acroForm)
                        check(form.getField("reviewer").valueAsString == "POCO test")
                        check(form.getField("newField").valueAsString == "Saved value")
                        check((form.getField("newCheck") as PDCheckBox).isChecked)
                        for (name in listOf("reviewer", "newField", "newCheck")) {
                            val field = form.getField(name)
                            check(field.widgets.all { it.appearance?.normalAppearance != null }) { "Missing saved appearance: $name" }
                        }
                    }
                    val flattened = export("flattened", listOf(PdfEditOperation.FillForm(mapOf("reviewer" to "Flattened value")), PdfEditOperation.FlattenForms))
                    PDDocument.load(flattened).use { pdf ->
                        check(pdf.documentCatalog.acroForm?.fields.isNullOrEmpty())
                        check((0 until pdf.numberOfPages).none { page -> pdf.getPage(page).annotations.any { it.subtype == "Widget" } })
                        check(PDFTextStripper().getText(pdf).contains("Flattened value"))
                    }
                }
                checkCase("merge preserves all pages and imported form data") {
                    val out = export("merged", listOf(PdfEditOperation.Merge(listOf(Uri.fromFile(source)))))
                    PDDocument.load(out).use { check(it.numberOfPages == 6) }
                }
                checkCase("watermark, numbering, headers and metadata remain searchable") {
                    val out = export("labels", listOf(
                        PdfEditOperation.Watermark("DRAFT"),
                        PdfEditOperation.PageNumbers("NX-", 101, 5),
                        PdfEditOperation.HeaderFooter("QA header", "QA footer"),
                        PdfEditOperation.Metadata("Edited title", "QA", "PDF editor", "test")
                    ))
                    PDDocument.load(out).use { pdf ->
                        val text = PDFTextStripper().getText(pdf)
                        check(text.contains("NX-00101") && text.contains("QA header"))
                        check(pdf.documentInformation.title == "Edited title")
                    }
                }
                checkCase("AES protection, password validation, permissions and owner unlock") {
                    val out = export("protected", listOf(PdfEditOperation.Protect("reader-pass", "owner-pass", false, false)))
                    check(runCatching { PDDocument.load(out).close() }.isFailure)
                    val readerInfo = PdfEditorEngine.inspect(context, Uri.fromFile(out), "reader-pass")
                    check(!readerInfo.canModify && !readerInfo.canExtract)
                    val retainedProtection = File(root, "protected-copy.pdf")
                    PdfEditorEngine.export(context, Uri.fromFile(out), Uri.fromFile(retainedProtection), emptyList(), "reader-pass")
                    PDDocument.load(retainedProtection, "reader-pass").use { check(it.isEncrypted && !it.currentAccessPermission.canExtractContent()) }
                    val rejected = runCatching {
                        PdfEditorEngine.export(context, Uri.fromFile(out), Uri.fromFile(File(root, "forbidden.pdf")), listOf(PdfEditOperation.Text(0, rect, "blocked")), "reader-pass")
                    }
                    check(rejected.isFailure) { "Restricted user must not modify a protected PDF" }
                    check(runCatching {
                        PdfEditorEngine.export(context, Uri.fromFile(out), Uri.fromFile(File(root, "implicit-unlock.pdf")), listOf(PdfEditOperation.Text(0, rect, "owner edit")), "owner-pass")
                    }.isFailure) { "Editing must not silently remove source password protection" }
                    val unlocked = File(root, "unlocked.pdf")
                    PdfEditorEngine.export(context, Uri.fromFile(out), Uri.fromFile(unlocked), listOf(PdfEditOperation.Unlock), "owner-pass")
                    PDDocument.load(unlocked).use { check(!it.isEncrypted) }
                }
                checkCase("resize and N-up retain content; compression produces renderable pages") {
                    val sized = export("resize", listOf(PdfEditOperation.Resize(612f, 792f)))
                    PDDocument.load(sized).use { check(it.getPage(0).mediaBox.width == 612f) }
                    val nup = export("nup", listOf(PdfEditOperation.NUp(2, 1)))
                    PDDocument.load(nup).use { check(it.numberOfPages == 2) }
                    val compressed = export("compressed", listOf(PdfEditOperation.Compress(70, 72, true)))
                    val image = PdfEditorEngine.render(context, Uri.fromFile(compressed), 0, 600)
                    check(image.width > 0)
                    for (x in 0 until image.width step 40) for (y in 0 until image.height step 40) {
                        val color = image.getPixel(x, y)
                        check(kotlin.math.abs(android.graphics.Color.red(color) - android.graphics.Color.green(color)) <= 3)
                        check(kotlin.math.abs(android.graphics.Color.green(color) - android.graphics.Color.blue(color)) <= 3)
                    }
                    image.recycle()
                }
                checkCase("bookmarks and mirroring preserve searchable content") {
                    val out = export("bookmarks", listOf(
                        PdfEditOperation.Bookmark("Added chapter", 1),
                        PdfEditOperation.Flip(listOf(0), true)
                    ))
                    PDDocument.load(out).use { pdf ->
                        val titles = pdf.documentCatalog.documentOutline.children().map { it.title }.toList()
                        check(titles.contains("Added chapter"))
                        check(PDFTextStripper().getText(pdf).contains("Review and annotate"))
                    }
                }
                checkCase("rotated, cropped pages with UserUnit retain physical sizing") {
                    val cropped = File(root, "cropped-source.pdf")
                    PDDocument.load(source).use { pdf ->
                        pdf.getPage(0).apply { cropBox = PDRectangle(20f, 30f, 280f, 370f); rotation = 90; userUnit = 2f }
                        pdf.save(cropped)
                    }
                    val info = PdfEditorEngine.inspect(context, Uri.fromFile(cropped))
                    check(info.pages[0].width == 740f && info.pages[0].height == 560f)
                    val output = File(root, "cropped-edited.pdf")
                    PdfEditorEngine.export(context, Uri.fromFile(cropped), Uri.fromFile(output), listOf(
                        PdfEditOperation.Text(0, rect, "Rotated text"),
                        PdfEditOperation.Resize(612f, 792f)
                    ))
                    PDDocument.load(output).use { check(PDFTextStripper().getText(it).contains("Rotated text")) }
                    val preview = PdfEditorEngine.render(context, Uri.fromFile(output), 0, 600)
                    check(preview.width > 0); preview.recycle()
                }
                checkCase("ZIP page images, embedded images, separate PDFs and UTF-8 text exports") {
                    for (kind in PdfExportKind.entries) {
                        val output = File(root, "export-${kind.name}.${if (kind == PdfExportKind.TEXT) "txt" else "zip"}")
                        val result = PdfExportEngine.export(context, Uri.fromFile(source), kind = kind, destination = Uri.fromFile(output))
                        check(result.entryCount > 0 && result.bytes == output.length())
                        if (kind == PdfExportKind.TEXT) check(output.readText().contains("Review and annotate"))
                        else ZipFile(output).use { zip ->
                            val entries = zip.entries().toList()
                            if (kind != PdfExportKind.EMBEDDED_IMAGES) check(entries.size == if (kind == PdfExportKind.SPLIT_HALVES) 6 else 3)
                            entries.forEach { entry ->
                                zip.getInputStream(entry).use { input ->
                                    if (kind in setOf(PdfExportKind.SPLIT_PAGES, PdfExportKind.SPLIT_BOOKMARKS, PdfExportKind.SPLIT_HALVES)) PDDocument.load(input).use { check(it.numberOfPages == 1) }
                                    else {
                                        val bitmap = requireNotNull(BitmapFactory.decodeStream(input))
                                        check(bitmap.width > 0); bitmap.recycle()
                                    }
                                }
                            }
                        }
                    }
                }
                checkCase("same-file aliases, invalid operations and cancellation preserve sources") {
                    val originalUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source)
                    check(runCatching {
                        PdfEditorEngine.export(context, originalUri, Uri.fromFile(source), listOf(PdfEditOperation.Rotate(listOf(0))))
                    }.isFailure)
                    check(runCatching { export("invalid", listOf(PdfEditOperation.PageOrder(emptyList()))) }.isFailure)
                    val output = File(root, "cancelled.pdf")
                    try {
                        PdfEditorEngine.export(context, Uri.fromFile(source), Uri.fromFile(output), emptyList(), cancelled = { true })
                        error("Expected cancellation")
                    } catch (_: CancellationException) { }
                    check(!output.exists() || output.length() == 0L)
                }
            }
            check(hash(source).contentEquals(originalHash)) { "Source PDF changed" }
            report = results.joinToString("\n", postfix = "\nAll ${results.size} PDF editor fixture checks passed\n")
        } catch (error: Throwable) {
            code = Activity.RESULT_CANCELED
            report = results.joinToString("\n") + "\nFAILED: ${error.stackTraceToString()}"
        } finally {
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
        instrumentation.finish(code, Bundle().apply { putString("stream", report) })
    }

    private suspend fun checkCase(name: String, action: suspend () -> Unit) {
        withTimeout(120_000) { action() }
        results += "PASS $name"
    }
    private suspend fun export(name: String, operations: List<PdfEditOperation>): File {
        val output = File(root, "$name.pdf")
        PdfEditorEngine.export(context, Uri.fromFile(source), Uri.fromFile(output), operations)
        return output
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
