package com.nixplorer.screen.storage

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageAnalysisCacheTest {
    @Test fun unchangedReportIsReusedAndChangesInvalidateIt() = runBlocking {
        val root = Files.createTempDirectory("analysis-root").toFile()
        val cache = Files.createTempDirectory("analysis-cache").toFile()
        try {
            val nested = root.resolve("nested").apply { mkdir() }
            val file = nested.resolve("sample").apply { writeText("abc") }
            var scans = 0
            suspend fun load(force: Boolean = false) = StorageAnalysisCache.load(root, cache, force, onScanning = { scans++ })
            assertEquals(3L, load().bytes)
            assertEquals(3L, load().bytes)
            assertEquals(1, scans)
            file.writeText("abcdef")
            var displayedBytes: Long? = null
            val updated = StorageAnalysisCache.load(root, cache,
                onCached = { displayedBytes = it.bytes },
                onScanning = { assertEquals(3L, displayedBytes); scans++ })
            assertEquals(6L, updated.bytes)
            assertEquals(2, scans)
            nested.resolve("new").writeText("x")
            nested.setLastModified(nested.lastModified() + 2000)
            assertEquals(7L, load().bytes)
            file.delete()
            assertEquals(1L, load().bytes)
            load(true)
            assertEquals(5, scans)
        } finally { root.deleteRecursively(); cache.deleteRecursively() }
    }
}
