package com.nixplorer.screen.main.tab.files.misc

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderSummaryTest {
    @Test fun includesNestedFilesAndTheirLatestModification() = runBlocking {
        val root = Files.createTempDirectory("folder-summary").toFile()
        try {
            val child = root.resolve("nested").apply { mkdir() }
            root.resolve("one").writeBytes(ByteArray(12))
            val newest = child.resolve("two").apply { writeBytes(ByteArray(30)) }
            val future = System.currentTimeMillis() + 60000
            assertTrue(newest.setLastModified(future))
            val summary = root.folderSummary()
            assertEquals(42L, summary.bytes)
            assertEquals(newest.lastModified(), summary.lastUpdated)
            assertTrue(summary.complete)
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyFolderHasZeroSizeAndItsOwnTimestamp() = runBlocking {
        val root = Files.createTempDirectory("folder-summary").toFile()
        try {
            val summary = root.folderSummary()
            assertEquals(0L, summary.bytes)
            assertEquals(root.lastModified(), summary.lastUpdated)
            assertTrue(summary.complete)
        } finally { root.deleteRecursively() }
    }
}
