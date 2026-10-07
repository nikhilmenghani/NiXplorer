package com.nixplorer.screen.storage

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmptyFoldersTest {
    @Test fun offersHighestEmptyAncestorAndNeverStorageRoot() = runBlocking {
        val root = Files.createTempDirectory("empty-tree").toFile()
        try {
            root.resolve("A/B/C").mkdirs()
            val report = findEmptyFolders(root)
            assertEquals(listOf(root.resolve("A")), report.folders.map { it.file })
            assertEquals(3, report.folders.single().folderCount)
        } finally { root.deleteRecursively() }
    }
    @Test fun filesInParentOnlyAllowEmptyChild() = runBlocking {
        val root = Files.createTempDirectory("mixed-tree").toFile()
        try {
            root.resolve("A/B/C").mkdirs()
            root.resolve("A/B/keep.txt").writeText("keep")
            root.resolve("hidden").mkdir()
            root.resolve("hidden/.keep").writeText("")
            assertEquals(listOf(root.resolve("A/B/C")), findEmptyFolders(root).folders.map { it.file })
        } finally { root.deleteRecursively() }
    }
    @Test fun recheckKeepsNewFilesAndDeletesOnlyStillEmptyTree() = runBlocking {
        val root = Files.createTempDirectory("recheck-tree").toFile()
        try {
            root.resolve("A/B/C").mkdirs()
            root.resolve("D/E").mkdirs()
            val report = findEmptyFolders(root)
            root.resolve("A/B/new.txt").writeText("new")
            val result = cleanEmptyFolders(root, report.folders, removeDirectory = { it.delete() })
            assertEquals(2, result.removed)
            assertEquals(1, result.skipped)
            assertTrue(root.resolve("A/B/new.txt").exists())
            assertTrue(root.resolve("A/B/C").exists())
            assertFalse(root.resolve("D").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsTargetsOutsideRootAndRootItself() = runBlocking {
        val root = Files.createTempDirectory("protected-root").toFile()
        val outside = Files.createTempDirectory("outside-root").toFile()
        try {
            val result = cleanEmptyFolders(root, listOf(EmptyFolder(root, 1), EmptyFolder(outside, 1)), removeDirectory = { it.delete() })
            assertEquals(0, result.removed)
            assertEquals(2, result.skipped)
            assertTrue(root.exists())
            assertTrue(outside.exists())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
}
