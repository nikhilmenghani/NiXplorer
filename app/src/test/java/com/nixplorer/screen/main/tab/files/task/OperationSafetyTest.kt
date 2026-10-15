package com.nixplorer.screen.main.tab.files.task

import com.nixplorer.screen.main.tab.files.zip.ArchiveEdits
import com.nixplorer.screen.main.tab.files.zip.ArchiveSafety
import com.nixplorer.screen.main.tab.files.provider.ProviderCapabilities
import com.nixplorer.screen.main.tab.files.provider.openProviderStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OperationSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(name: String, content: String) = File(temporary.root, name).apply { writeText(content) }
    private fun zip(name: String, entries: List<Pair<String, String>>) = File(temporary.root, name).apply {
        ZipOutputStream(outputStream()).use { out -> entries.forEach { (path, content) ->
            out.putNextEntry(ZipEntry(path)); out.write(content.toByteArray()); out.closeEntry()
        } }
    }

    @Test fun rejectsTraversalAbsoluteBackslashAndDrivePaths() {
        listOf("../outside", "a/../../outside", "/outside", "C:\\outside", "\\\\server\\share", "a\\..\\outside", "a\u0000b").forEach { path ->
            assertTrue(path, runCatching { SafeFileOperations.contained(temporary.root, path) }.isFailure)
        }
    }
    @Test fun ordinaryZipCannotExposeTraversalEntry() {
        val archive = zip("bad.zip", listOf("../outside" to "bad"))
        assertTrue(runCatching { ArchiveSafety.validate(archive) }.isFailure)
        assertFalse(File(temporary.root.parentFile, "outside").exists())
    }
    @Test fun duplicateZipHeadersAndUnixSymlinksAreRejected() {
        val duplicate = File(temporary.root, "duplicate.zip")
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(duplicate).use { out ->
            repeat(2) { out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry("same")); out.write(byteArrayOf(1)); out.closeArchiveEntry() }
        }
        assertTrue(runCatching { ArchiveSafety.validate(duplicate) }.isFailure)
        val linked = File(temporary.root, "link.zip")
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(linked).use { out ->
            val entry = org.apache.commons.compress.archivers.zip.ZipArchiveEntry("link").apply { unixMode = 0xa1ff }
            out.putArchiveEntry(entry); out.write("../outside".toByteArray()); out.closeArchiveEntry()
        }
        assertTrue(runCatching { ArchiveSafety.validate(linked) }.isFailure)
    }
    @Test fun archiveCompressionRatioAndCorruptChecksumsAreRejected() {
        val bomb = zip("bomb.zip", listOf("large" to "0".repeat(2 * 1024 * 1024)))
        assertTrue(runCatching { ArchiveSafety.validate(bomb) }.isFailure)
        val corrupt = zip("corrupt.zip", listOf("file" to "contents"))
        val bytes = corrupt.readBytes()
        val central = bytes.indices.first { i -> i + 4 <= bytes.size && bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 0xff).toByte()
        corrupt.writeBytes(bytes)
        assertTrue(runCatching { ArchiveSafety.validate(corrupt, readData = true) }.isFailure)
    }
    @Test fun duplicateAndFileDirectoryConflictsRejectedInEitherOrder() {
        listOf(listOf("a", "a"), listOf("a", "a/b"), listOf("a/b", "a")).forEach { paths ->
            val validator = ArchiveSafety.Paths()
            assertTrue(runCatching { paths.forEach { validator.add(it, false) } }.isFailure)
        }
        val validator = ArchiveSafety.Paths()
        validator.add("a/b", false); validator.add("a/", true)
    }
    @Test fun encryptedZipTraversalIsRejectedWithoutChangingOriginal() = kotlinx.coroutines.runBlocking {
        val archive = File(temporary.root, "encrypted.zip")
        net.lingala.zip4j.ZipFile(archive, "password".toCharArray()).use { zip ->
            zip.addStream("fixture".byteInputStream(), net.lingala.zip4j.model.ZipParameters().apply {
                fileNameInZip = "safe"; isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
            })
        }
        val bytes = archive.readBytes()
        val safe = "safe".toByteArray()
        bytes.indices.filter { i -> i + 4 <= bytes.size && bytes.copyOfRange(i, i + 4).contentEquals(safe) }
            .forEach { "../x".toByteArray().copyInto(bytes, it) }
        archive.writeBytes(bytes)
        assertTrue(runCatching { com.nixplorer.screen.archive.unlockZip(archive, "password".toCharArray()) }.isFailure)
        assertArrayEquals(bytes, archive.readBytes())
        assertFalse(File(temporary.root.parentFile, "x").exists())
    }
    @Test fun convertedSevenZipTraversalIsRejected() = kotlinx.coroutines.runBlocking {
        val archive = File(temporary.root, "traversal.7z")
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(archive).use { output ->
            val entry = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry().apply { name = "../x"; size = 1 }
            output.putArchiveEntry(entry); output.write(byteArrayOf(1)); output.closeArchiveEntry()
        }
        assertTrue(runCatching { com.nixplorer.screen.archive.unlockSevenZip(archive, null) }.isFailure)
        assertFalse(File(temporary.root, "traversal.7z.zip").exists())
    }
    @Test fun nestedSymlinkDestinationIsRejected() {
        val outside = temporary.newFolder("outside")
        val root = temporary.newFolder("dest")
        try { Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath()) }
        catch (e: Exception) { org.junit.Assume.assumeNoException("Host cannot create symlinks; Android test covers this", e) }
        assertTrue(runCatching { SafeFileOperations.contained(root, "link/escaped") }.isFailure)
        assertFalse(File(outside, "escaped").exists())
    }
    @Test fun failedAndFullDiskWritesKeepExistingDestination() {
        val destination = file("existing", "original")
        val result = runCatching {
            SafeFileOperations.stagedWrite(temporary.root, destination.name, true) { stage ->
                stage.writeText("partial"); throw IOException("No space left on device")
            }
        }
        assertTrue(result.isFailure); assertEquals("original", destination.readText())
        assertFalse(temporary.root.listFiles()!!.any { it.name.startsWith(".nix-stage-") })
    }
    @Test fun failedDirectoryCreationDoesNotSucceed() {
        val blockingFile = file("parent", "file")
        assertTrue(runCatching { SafeFileOperations.directory(File(blockingFile, "child")) }.isFailure)
    }
    @Test fun cancellationDiscardsIncompleteWriteAndPreservesOriginal() {
        val destination = file("existing", "original")
        var cancelled = false
        assertTrue(runCatching { SafeFileOperations.stagedWrite(temporary.root, destination.name, true, { cancelled }) { stage ->
            stage.writeText("partial"); cancelled = true
        } }.exceptionOrNull() is CancellationException)
        assertEquals("original", destination.readText())
    }
    @Test fun sizeLimitStopsStreamAndRetainsDestination() {
        file("existing", "original")
        assertTrue(runCatching { SafeFileOperations.stagedWrite(temporary.root, "existing", true) { stage ->
            SafeFileOperations.stream("123456".byteInputStream(), stage, 3)
        } }.isFailure)
        assertEquals("original", File(temporary.root, "existing").readText())
    }
    @Test fun archiveEditsLeavePredictableSiblingUntouched() {
        val archive = zip("archive.zip", listOf("keep" to "safe", "remove" to "gone"))
        val sibling = file("archive_temp.zip", "personal sibling")
        ArchiveEdits.update(archive) { stage -> net.lingala.zip4j.ZipFile(stage).use { it.removeFile("remove") } }
        assertEquals("personal sibling", sibling.readText())
        java.util.zip.ZipFile(archive).use { assertNotNull(it.getEntry("keep")); assertNull(it.getEntry("remove")) }
    }
    @Test fun invalidCompletedArchiveDoesNotReplaceOriginal() {
        val archive = zip("archive.zip", listOf("keep" to "safe"))
        val before = archive.readBytes()
        assertTrue(runCatching { ArchiveEdits.update(archive) { it.writeText("broken") } }.isFailure)
        assertArrayEquals(before, archive.readBytes())
    }
    @Test fun cancelledArchiveEditRetainsOriginal() {
        val archive = zip("archive.zip", listOf("keep" to "safe"))
        val before = archive.readBytes()
        var cancelled = false
        assertTrue(runCatching { ArchiveEdits.update(archive, { cancelled }) { cancelled = true } }.isFailure)
        assertArrayEquals(before, archive.readBytes())
    }
    @Test fun failedReplacementRestoresOriginal() {
        val original = file("original", "original")
        val stage = file("stage", "replacement")
        assertTrue(runCatching { SafeFileOperations.replace(stage, original, move = { from, to, atomic ->
            if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "fixture")
            if (from == stage) throw IOException("Injected publication failure")
            Files.move(from.toPath(), to.toPath())
        }) }.isFailure)
        assertEquals("original", original.readText()); assertEquals("replacement", stage.readText())
    }
    @Test fun failedRestoreKeepsRecoverableOriginalAndReplacement() {
        val original = file("original", "original")
        val stage = file("stage", "replacement")
        assertTrue(runCatching { SafeFileOperations.replace(stage, original, move = { from, to, atomic ->
            if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "fixture")
            if (from == stage || from.name.startsWith(".nix-backup-")) throw IOException("Injected failure")
            Files.move(from.toPath(), to.toPath())
        }) }.isFailure)
        assertEquals("replacement", stage.readText())
        assertEquals("original", temporary.root.listFiles()!!.first { it.name.startsWith(".nix-backup-") }.readText())
        val notes = SafeFileOperations.reconcileReplacement(original)
        assertTrue(notes.any { it.contains("Restored original") }); assertEquals("original", original.readText())
    }
    @Test fun simulatedProcessDeathBetweenRenamesRecoversOriginalWithoutReplayingDelete() {
        val original = file("original", "original")
        val stage = file("stage", "replacement")
        try {
            SafeFileOperations.replace(stage, original, move = { from, to, atomic ->
                if (atomic) throw AtomicMoveNotSupportedException(from.path, to.path, "fixture")
                Files.move(from.toPath(), to.toPath())
                if (from == original) throw AssertionError("Simulated process termination")
            })
            fail("Crash expected")
        } catch (_: AssertionError) { }
        assertFalse(original.exists())
        SafeFileOperations.reconcileReplacement(original)
        assertEquals("original", original.readText()); assertEquals("replacement", stage.readText())
    }
    @Test fun mixedOutcomesAndCancellationAreNotSuccess() {
        assertEquals(TaskStatus.SUCCESS, copyOutcome(listOf(TaskContentStatus.SUCCESS)))
        assertEquals(TaskStatus.PARTIAL_FAILURE, copyOutcome(listOf(TaskContentStatus.SUCCESS, TaskContentStatus.FAILED)))
        assertEquals(TaskStatus.FAILED, copyOutcome(listOf(TaskContentStatus.FAILED)))
        assertEquals(TaskStatus.CANCELLED, copyOutcome(listOf(TaskContentStatus.SUCCESS), true))
        assertEquals(TaskStatus.FAILED, copyOutcome(listOf(TaskContentStatus.PENDING)))
    }
    @Test fun recreatedSourceDuringCleanupIsRestoredAndNeverDeleted() {
        val source = file("source", "original")
        val destination = file("destination", "original")
        val expected = SafeFileOperations.identity(source)
        if (expected.key == null) {
            assertTrue(runCatching { SafeFileOperations.deleteVerifiedSource(source, destination, expected) }.isFailure)
            assertEquals("original", source.readText())
            return // Android instrumentation exercises the dev/inode race.
        }
        val old = File(temporary.root, "old-source")
        assertTrue(runCatching {
            SafeFileOperations.deleteVerifiedSource(source, destination, expected, beforeQuarantine = {
                Files.move(source.toPath(), old.toPath()); source.writeText("new unrelated file")
            })
        }.isFailure)
        assertEquals("new unrelated file", source.readText()); assertEquals("original", old.readText())
    }
    @Test fun verifiedMoveDeletesOnlyTheCopiedSource() {
        val source = file("source", "original")
        val destination = file("destination", "original")
        val expected = SafeFileOperations.identity(source)
        if (expected.key == null) {
            assertTrue(runCatching { SafeFileOperations.deleteVerifiedSource(source, destination, expected) }.isFailure)
            assertTrue(source.exists())
            return
        }
        SafeFileOperations.deleteVerifiedSource(source, destination, expected)
        assertFalse(source.exists()); assertEquals("original", destination.readText())
    }
    @Test fun changedDestinationPreventsSourceDeletion() {
        val source = file("source", "original")
        val destination = file("destination", "changed")
        assertTrue(runCatching { SafeFileOperations.deleteVerifiedSource(source, destination, SafeFileOperations.identity(source)) }.isFailure)
        assertEquals("original", source.readText())
    }
    @Test fun samePathDescendantAndOverlappingSourcesAreRejected() {
        val folder = temporary.newFolder("folder")
        val child = File(folder, "child").apply { mkdir() }
        assertTrue(runCatching { SafeFileOperations.validateCopyPaths(listOf(folder), child) }.isFailure)
        assertTrue(runCatching { SafeFileOperations.validateCopyPaths(listOf(folder), folder) }.isFailure)
        val other = temporary.newFolder("other")
        assertTrue(runCatching { SafeFileOperations.validateCopyPaths(listOf(folder, child), other) }.isFailure)
        SafeFileOperations.validateCopyPaths(listOf(folder), other)
    }
    @Test fun concurrentConflictDecisionsRemainIsolated() {
        val queue = ConflictQueue<String>()
        val latch = CountDownLatch(2)
        val workers = listOf("one", "two").map { id -> Thread { queue.put(id, "decision-$id"); latch.countDown() }.apply { start() } }
        latch.await(); workers.forEach { it.join() }
        val first = queue.first()!!
        queue.remove(first.first)
        val second = queue.first()!!
        assertNotEquals(first.first, second.first); assertEquals("decision-${second.first}", second.second)
    }
    @Test fun concurrentArchiveEditsDoNotLoseEachOthersChanges() {
        val archive = zip("archive.zip", listOf("a" to "1", "b" to "2", "keep" to "3"))
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers = listOf("a", "b").map { name -> Thread {
            try { ArchiveEdits.update(archive) { stage -> net.lingala.zip4j.ZipFile(stage).use { it.removeFile(name) } } }
            catch (error: Throwable) { errors.add(error) }
        }.apply { start() } }
        workers.forEach { it.join() }
        assertTrue(errors.toString(), errors.isEmpty())
        java.util.zip.ZipFile(archive).use { assertNull(it.getEntry("a")); assertNull(it.getEntry("b")); assertNotNull(it.getEntry("keep")) }
    }
    @Test fun durableJournalKeepsPerItemEvidenceAcrossNewInstance() {
        val directory = temporary.newFolder("journal")
        val item = OperationItemRecord("source", "destination", "SUCCESS", null, true, false, null)
        OperationJournal(directory).save(OperationRecord("fixture-task", "Move", "move", listOf("source"), "destination", "RUNNING", "", listOf(item)))
        val loaded = OperationJournal(directory).load().single()
        assertEquals("RUNNING", loaded.status); assertTrue(loaded.items.single().copied); assertFalse(loaded.items.single().sourceDeleted)
    }
    @Test fun incrementalItemCheckpointRecoversSuccessWithoutReplayingIncompleteAppend() {
        val directory = temporary.newFolder("item-journal")
        val journal = OperationJournal(directory)
        journal.save(OperationRecord("fixture-items", "Copy", "copy", listOf("one", "two"), "dest", "RUNNING", ""))
        journal.saveItem("fixture-items", OperationItemRecord("one", "dest/one", "SUCCESS", null, true, false, null))
        journal.saveItem("fixture-items", OperationItemRecord("two", "dest/two", "FAILED", "write failed", false, false, null))
        File(directory, "fixture-items.items.jsonl").appendText("{\"source\":")
        val loaded = OperationJournal(directory).load().single()
        assertEquals(2, loaded.items.size)
        assertTrue(loaded.items.first { it.source == "one" }.copied)
        assertEquals("write failed", loaded.items.first { it.source == "two" }.error)
    }
    @Test fun missingAndUnreadableLocationsAreNotEmptyFolders() {
        assertTrue(runCatching { SafeFileOperations.walk(File(temporary.root, "disconnected")) { } }.isFailure)
        var count = 0
        SafeFileOperations.walk(temporary.newFolder("empty")) { count++ }
        assertEquals(1, count)
    }
    @Test fun largeDirectoryTraversalIsCancellableAndStopsAtDepthLimit() {
        val root = temporary.newFolder("tree")
        repeat(2000) { File(root, "$it.txt").writeText("fixture") }
        var visited = 0
        assertTrue(runCatching { SafeFileOperations.walk(root, { visited >= 37 }) { visited++ } }.exceptionOrNull() is CancellationException)
        assertEquals(37, visited)
        var current = temporary.newFolder("deep")
        // Keep paths below Windows' long-path limits; host-depth test uses malformed archive path too.
        assertTrue(runCatching { SafeFileOperations.relativePath(List(SafeFileOperations.MAX_DEPTH + 1) { "a" }.joinToString("/")) }.isFailure)
    }
    @Test fun revokedProviderAccessDisconnectedProviderAndUnsafeReplacementFailClearly() {
        val readable = ProviderCapabilities(true, false, false, false, false, false)
        assertTrue(runCatching { openProviderStream(readable) { throw SecurityException("revoked") } }.exceptionOrNull()!!.message!!.contains("revoked"))
        assertTrue(runCatching { openProviderStream(readable) { null } }.isFailure)
        assertTrue(runCatching { readable.requireSafeReplacement() }.isFailure)
        val denied = readable.copy(readable = false)
        var opened = false
        assertTrue(runCatching { openProviderStream(denied) { opened = true; "x".byteInputStream() } }.isFailure)
        assertFalse(opened)
    }
}
