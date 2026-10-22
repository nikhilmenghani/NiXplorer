package com.nixplorer.screen.main.tab.files.conversion

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversionCatalogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun mixedImageBatchCanUseAnyImageTarget() {
        val files = listOf("photo.JPG", "drawing.png", "picture.webp").map { File(temporary.root, it) }
        assertEquals(listOf(ConversionFormat.JPEG, ConversionFormat.PNG, ConversionFormat.WEBP), ConversionCatalog.commonFormats(files))
    }

    @Test fun batchTargetsAreIntersectionAndFoldersAreRejected() {
        assertEquals(listOf(ConversionFormat.M4A, ConversionFormat.WAV), ConversionCatalog.commonFormats(listOf(File("video.mp4"), File("audio.m4a"))))
        assertTrue(ConversionCatalog.commonFormats(listOf(File("document.txt"), File("photo.png"))).isEmpty())
        assertTrue(ConversionCatalog.commonFormats(emptyList()).isEmpty())
        assertTrue(ConversionCatalog.formatsFor(temporary.newFolder("folder.jpg")).isEmpty())
    }

    @Test fun sourceFamiliesOfferOnlyImplementedTransformations() {
        assertEquals(listOf(ConversionFormat.TXT), ConversionCatalog.formatsFor(File("scan.pdf")))
        assertTrue(ConversionCatalog.formatsFor(File("legacy.doc")).isEmpty())
        assertTrue(ConversionCatalog.formatsFor(File("rich.rtf")).isEmpty())
        assertEquals(listOf(ConversionFormat.M4A, ConversionFormat.WAV), ConversionCatalog.formatsFor(File("music.flac")))
        assertEquals(listOf(ConversionFormat.MP4, ConversionFormat.M4A, ConversionFormat.WAV), ConversionCatalog.formatsFor(File("movie.mp4")))
        assertTrue(ConversionCatalog.formatsFor(File("report.docx")).contains(ConversionFormat.PDF))
        assertTrue(ConversionCatalog.formatsFor(File("report.docx")).contains(ConversionFormat.DOCX))
    }

    @Test fun mixedAudioAndTextBatchesCanNormalizeTheirOwnFormats() {
        assertEquals(listOf(ConversionFormat.M4A, ConversionFormat.WAV),
            ConversionCatalog.commonFormats(listOf(File("music.m4a"), File("recording.wav"))))
        assertEquals(listOf(ConversionFormat.TXT),
            ConversionCatalog.commonFormats(listOf(File("report.pdf"), File("notes.txt"))))
        assertEquals(listOf(ConversionFormat.PDF, ConversionFormat.TXT, ConversionFormat.HTML, ConversionFormat.DOCX),
            ConversionCatalog.commonFormats(listOf(File("notes.txt"), File("page.html"), File("report.docx"))))
    }

    @Test fun newImageDecodersAreGatedByAndroidVersion() {
        assertTrue(ConversionCatalog.formatsFor(File("photo.heic"), 27).isEmpty())
        assertEquals(3, ConversionCatalog.formatsFor(File("photo.heif"), 28).size)
        assertTrue(ConversionCatalog.formatsFor(File("photo.avif"), 30).isEmpty())
        assertEquals(3, ConversionCatalog.formatsFor(File("photo.avif"), 31).size)
    }

    @Test fun outputNamesRespectExistingFilesBatchReservationsAndUtf8Limit() {
        temporary.newFile("photo.jpg")
        val source = File(temporary.root, "photo.png")
        val first = ConversionOutputs.nextAvailable(source, temporary.root, ConversionFormat.JPEG)
        assertEquals("photo (1).jpg", first.name)
        val second = ConversionOutputs.nextAvailable(source, temporary.root, ConversionFormat.JPEG, setOf(first.path))
        assertEquals("photo (2).jpg", second.name)
        assertFalse(first.exists())
        val long = ConversionOutputs.nextAvailable(File("😀".repeat(100) + ".png"), temporary.root, ConversionFormat.JPEG)
        assertTrue(long.name.toByteArray(Charsets.UTF_8).size <= 240)
        assertFalse(long.name.contains('\uFFFD'))
    }

    @Test fun namePlannerSeparatesMatchingBasenamesAndPreviouslyPublishedOutputs() {
        val alreadyPublished = temporary.newFile("sample.jpg")
        val planner = ConversionOutputs.Planner(temporary.root, ConversionFormat.JPEG, listOf(alreadyPublished))
        assertEquals("sample (1).jpg", planner.next(File("first/sample.png")).name)
        assertEquals("sample (2).jpg", planner.next(File("second/sample.webp")).name)
        assertEquals("sample (1) (1).jpg", planner.next(File("third/sample (1).png")).name)
        assertEquals(1, temporary.root.listFiles()!!.size)
    }
}
