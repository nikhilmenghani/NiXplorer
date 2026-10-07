package com.nixplorer.screen.storage

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageCategoriesTest {
    @Test fun categoryTotalsIncludeFilesOutsideGlobalRanking() = runBlocking {
        val root = Files.createTempDirectory("categories").toFile()
        try {
            root.resolve("big.mp4").writeBytes(ByteArray(100))
            root.resolve("small.PDF").writeBytes(ByteArray(20))
            root.resolve("smaller.docx").writeBytes(ByteArray(10))
            root.resolve("install.apk").writeBytes(ByteArray(5))
            val report = analyzeFiles(root, limit = 1)
            assertEquals(135L, report.bytes)
            assertEquals(report.bytes, report.categories.sumOf { it.bytes })
            assertEquals(report.fileCount, report.categories.sumOf { it.count })
            assertEquals("big.mp4", report.files.single().file.name)
            val documents = report.categories.single { it.category == StorageCategory.DOCUMENTS }
            assertEquals(30L, documents.bytes)
            assertEquals(2L, documents.count)
            assertEquals("small.PDF", documents.largest.single().file.name)
            assertEquals(5L, report.categories.single { it.category == StorageCategory.APKS }.bytes)
        } finally { root.deleteRecursively() }
    }
    @Test fun emptyCategoriesRemainAvailable() = runBlocking {
        val root = Files.createTempDirectory("empty-categories").toFile()
        try {
            val report = analyzeFiles(root)
            assertEquals(StorageCategory.entries.size, report.categories.size)
            assertTrue(report.categories.all { it.bytes == 0L && it.count == 0L && it.largest.isEmpty() })
        } finally { root.deleteRecursively() }
    }
}
