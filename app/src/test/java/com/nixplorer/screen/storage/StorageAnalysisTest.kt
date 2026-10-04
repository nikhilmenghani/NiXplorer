package com.nixplorer.screen.storage

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageAnalysisTest {
    @Test fun ranksFilesAndAggregatesNestedFoldersWithoutDoubleCounting() = runBlocking {
        val root = Files.createTempDirectory("storage-analysis").toFile()
        try {
            val nested = root.resolve("nested/deep").apply { mkdirs() }
            root.resolve("small").writeBytes(ByteArray(5))
            root.resolve("nested/medium").writeBytes(ByteArray(15))
            nested.resolve("large").writeBytes(ByteArray(30))
            val report = analyzeFiles(root)
            assertEquals(50L, report.bytes)
            assertEquals(3L, report.fileCount)
            assertEquals(listOf(30L, 15L, 5L), report.files.map { it.bytes })
            assertEquals(listOf(45L, 30L), report.folders.map { it.bytes })
            assertEquals(0, report.skippedFolders)
            assertFalse(report.folders.any { it.file == root })
        } finally { root.deleteRecursively() }
    }

    @Test fun limitsRankingsButStillCountsAllFiles() = runBlocking {
        val root = Files.createTempDirectory("storage-analysis").toFile()
        try {
            repeat(10) { root.resolve("file$it").writeBytes(ByteArray(it + 1)) }
            val report = analyzeFiles(root, limit = 2)
            assertEquals(55L, report.bytes)
            assertEquals(10L, report.fileCount)
            assertEquals(listOf(10L, 9L), report.files.map { it.bytes })
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyFoldersReturnEmptyRankings() = runBlocking {
        val root = Files.createTempDirectory("storage-analysis").toFile()
        try {
            val report = analyzeFiles(root)
            assertEquals(0L, report.bytes)
            assertTrue(report.files.isEmpty())
            assertTrue(report.folders.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
