package com.nixplorer.screen.viewer.pdf.editor

import android.net.Uri

/** Positions are fractions of the displayed, rotated page: (0,0) at top-left, (1,1) at bottom-right. */
data class PdfPoint(val x: Float, val y: Float)
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
enum class PdfShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW }
enum class PdfMarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT }
data class PdfPageInfo(val index: Int, val width: Float, val height: Float, val rotation: Int)
data class PdfFormField(val name: String, val type: String, val value: String, val readOnly: Boolean)
data class PdfDocumentInfo(
    val pages: List<PdfPageInfo>, val fields: List<PdfFormField>, val encrypted: Boolean,
    val signed: Boolean, val canModify: Boolean, val canAssemble: Boolean,
    val canExtract: Boolean, val metadata: Map<String, String>
) { val pageCount: Int get() = pages.size }

/** Applied in list order. Page indices refer to the document after earlier operations. */
sealed interface PdfEditOperation {
    data class Text(val page: Int, val rect: PdfRect, val text: String, val size: Float = 16f,
                    val color: Long = 0xff000000, val bold: Boolean = false) : PdfEditOperation
    data class Image(val page: Int, val rect: PdfRect, val uri: Uri,
                     val signature: Boolean = false) : PdfEditOperation
    data class Ink(val page: Int, val points: List<PdfPoint>, val color: Long = 0xff000000,
                   val width: Float = 2f) : PdfEditOperation
    data class Shape(val page: Int, val rect: PdfRect, val kind: PdfShapeKind,
                     val color: Long = 0xff000000, val fill: Long? = null,
                     val width: Float = 2f) : PdfEditOperation
    data class Markup(val page: Int, val rect: PdfRect, val kind: PdfMarkupKind,
                      val color: Long = 0xffffff00) : PdfEditOperation
    /** Paints over content; underlying text remains. Use Redact for irreversible removal. */
    data class Whiteout(val page: Int, val rect: PdfRect) : PdfEditOperation
    /** Rasterizes the affected page at 144dpi, removes the region, then adds optional new text. */
    data class Redact(val page: Int, val rect: PdfRect, val replacement: String = "",
                      val size: Float = 16f) : PdfEditOperation
    data class Link(val page: Int, val rect: PdfRect, val url: String) : PdfEditOperation
    data class FillForm(val values: Map<String, String>) : PdfEditOperation
    data class TextField(val page: Int, val rect: PdfRect, val name: String,
                         val multiline: Boolean = false) : PdfEditOperation
    data class Checkbox(val page: Int, val rect: PdfRect, val name: String,
                        val checked: Boolean = false) : PdfEditOperation
    /** Reorder/delete/duplicate in one command; an index may occur multiple times. */
    data class PageOrder(val pages: List<Int>) : PdfEditOperation
    data class Rotate(val pages: List<Int>, val degrees: Int = 90) : PdfEditOperation
    data class BlankPage(val afterPage: Int, val width: Float = 595f, val height: Float = 842f) : PdfEditOperation
    data class Merge(val sources: List<Uri>) : PdfEditOperation
    data class Crop(val page: Int, val rect: PdfRect) : PdfEditOperation
    data class Watermark(val text: String, val opacity: Float = .25f, val angle: Float = 35f,
                         val size: Float = 48f, val color: Long = 0xff777777) : PdfEditOperation
    data class PageNumbers(val prefix: String = "", val start: Int = 1, val digits: Int = 0,
                           val size: Float = 11f) : PdfEditOperation
    data class HeaderFooter(val header: String = "", val footer: String = "", val size: Float = 11f) : PdfEditOperation
    data class Metadata(val title: String = "", val author: String = "", val subject: String = "",
                        val keywords: String = "") : PdfEditOperation
    data object FlattenForms : PdfEditOperation
    data object RemoveAnnotations : PdfEditOperation
    data class Protect(val userPassword: String, val ownerPassword: String, val allowPrinting: Boolean = true,
                       val allowCopy: Boolean = false) : PdfEditOperation
    data object Unlock : PdfEditOperation
    /** Full-page image rewrite; searchable text, interactive forms, links and vector detail are lost. */
    data class Compress(val quality: Int = 75, val dpi: Int = 120, val grayscale: Boolean = false) : PdfEditOperation
    data class Resize(val width: Float = 595f, val height: Float = 842f) : PdfEditOperation
    data class NUp(val columns: Int = 2, val rows: Int = 1) : PdfEditOperation
    /** Mirrors the displayed pages; form controls and visible annotations are flattened first. */
    data class Flip(val pages: List<Int>, val horizontal: Boolean = true) : PdfEditOperation
    data class Bookmark(val title: String, val page: Int) : PdfEditOperation
    data object RemoveBookmarks : PdfEditOperation
}
