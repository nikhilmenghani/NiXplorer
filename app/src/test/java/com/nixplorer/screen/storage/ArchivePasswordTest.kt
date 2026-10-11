package com.nixplorer.screen.storage

import com.nixplorer.screen.archive.ArchivePasswordRequired
import com.nixplorer.screen.archive.unlockZip
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.*
import org.junit.Test

class ArchivePasswordTest {
    @Test fun encryptedZipPromptsRetriesAndUnlocksWithoutChangingOriginal() = runBlocking {
        val folder = Files.createTempDirectory("archive-password").toFile()
        try {
            val content = folder.resolve("settings.config").apply { writeText("enabled=true") }
            val source = folder.resolve("protected.zip")
            ZipFile(source, "test-password".toCharArray()).use { archive ->
                archive.addFile(content, ZipParameters().apply { isEncryptFiles = true; encryptionMethod = EncryptionMethod.AES })
            }
            val original = source.readBytes()
            assertTrue(runCatching { unlockZip(source, null) }.exceptionOrNull() is ArchivePasswordRequired)
            assertTrue(runCatching { unlockZip(source, "wrong".toCharArray()) }.exceptionOrNull() is ArchivePasswordRequired)
            val unlocked = unlockZip(source, "test-password".toCharArray())
            java.util.zip.ZipFile(unlocked).use { archive ->
                assertEquals("enabled=true", archive.getInputStream(archive.getEntry("settings.config")).bufferedReader().readText())
            }
            assertArrayEquals(original, source.readBytes())
        } finally { folder.deleteRecursively() }
    }
}
