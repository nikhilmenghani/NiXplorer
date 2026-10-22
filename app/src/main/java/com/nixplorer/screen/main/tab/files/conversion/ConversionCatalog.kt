package com.nixplorer.screen.main.tab.files.conversion

import android.os.Build
import java.io.File
import java.util.Locale

enum class ConversionFormat(val extension: String, val label: String) {
    JPEG("jpg", "JPEG"),
    PNG("png", "PNG"),
    WEBP("webp", "WebP"),
    PDF("pdf", "PDF"),
    TXT("txt", "Text (TXT)"),
    HTML("html", "HTML"),
    DOCX("docx", "Word (DOCX)"),
    MP4("mp4", "Video (MP4)"),
    M4A("m4a", "Audio (M4A)"),
    WAV("wav", "Audio (WAV)")
}

/** Only offers transformations implemented locally. A decoder can still reject damaged files/codecs. */
object ConversionCatalog {
    private val imageFormats = listOf(ConversionFormat.JPEG, ConversionFormat.PNG, ConversionFormat.WEBP)
    private val documentFormats = listOf(ConversionFormat.PDF, ConversionFormat.TXT, ConversionFormat.HTML, ConversionFormat.DOCX)
    private val audioFormats = listOf(ConversionFormat.M4A, ConversionFormat.WAV)
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private val documentExtensions = setOf("txt", "md", "markdown", "csv", "log", "html", "htm", "docx", "odt")
    private val videoExtensions = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "3gpp", "ts", "mts", "m2ts")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")

    fun formatsFor(file: File): List<ConversionFormat> = formatsFor(file, Build.VERSION.SDK_INT)

    internal fun formatsFor(file: File, sdkInt: Int): List<ConversionFormat> {
        if (file.isDirectory) return emptyList()
        val extension = file.extension.lowercase(Locale.ROOT)
        val candidates = when {
            extension in imageExtensions -> imageFormats
            extension in setOf("heic", "heif") && sdkInt >= 28 -> imageFormats
            extension == "avif" && sdkInt >= 31 -> imageFormats
            extension == "pdf" -> listOf(ConversionFormat.TXT)
            extension in documentExtensions -> documentFormats
            extension in videoExtensions -> listOf(ConversionFormat.MP4) + audioFormats
            extension in audioExtensions -> audioFormats
            else -> emptyList()
        }
        // Re-encoding is useful for normalizing codecs, PCM/encoding, text layout or image quality.
        // Keeping the current format also lets mixed batches share one output format.
        return candidates
    }

    /** A batch has one target format; each selected file must support that target. */
    fun commonFormats(files: List<File>): List<ConversionFormat> {
        if (files.isEmpty()) return emptyList()
        return files.map(::formatsFor).reduce { common, formats -> common.filter { it in formats } }
    }

    fun notice(format: ConversionFormat): String? = when (format) {
        ConversionFormat.JPEG -> "Transparency is replaced with white. Animated images use the first frame. Image metadata is removed."
        ConversionFormat.PNG -> "Animated images use the first frame. Image metadata is removed."
        ConversionFormat.WEBP -> "WebP uses lossy compression. Animated images use the first frame. Image metadata is removed."
        ConversionFormat.PDF, ConversionFormat.TXT, ConversionFormat.HTML, ConversionFormat.DOCX ->
            "Documents keep readable text and paragraphs. Layout, images and complex formatting are not preserved. PDF to text requires embedded text; scanned pages are not OCR processed."
        ConversionFormat.MP4 -> "Video is encoded as H.264 with AAC audio. Subtitle tracks and metadata are omitted. Supported input codecs depend on this device."
        ConversionFormat.M4A -> "Audio is encoded as AAC. Video, other tracks and metadata are omitted. Supported input codecs depend on this device."
        ConversionFormat.WAV -> "Audio is decoded to uncompressed 16-bit PCM. WAV files can be large. Video, other tracks and metadata are omitted. Supported input codecs depend on this device."
    }
}
