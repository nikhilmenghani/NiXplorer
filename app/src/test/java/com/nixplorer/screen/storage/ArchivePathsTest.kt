package com.nixplorer.screen.storage

import com.nixplorer.screen.archive.safeArchivePath
import org.junit.Assert.*
import org.junit.Test

class ArchivePathsTest {
    @Test fun normalizesSafePaths() {
        assertEquals("folder/settings.config", safeArchivePath("./folder\\settings.config"))
        assertEquals("folder", safeArchivePath("folder/"))
    }
    @Test fun rejectsEscapingAndAbsolutePaths() {
        listOf("../outside", "a/../../outside", "/outside", "C:\\outside", "a\u0000b").forEach {
            assertTrue(runCatching { safeArchivePath(it) }.isFailure)
        }
    }
}
