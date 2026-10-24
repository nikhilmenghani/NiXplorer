package com.nixplorer.screen.share

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedFileNamesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun rejectsTraversalAndEmptyNames() {
        assertEquals("photo.jpg", sharedFileName("../../photo.jpg", "fallback"))
        assertEquals("file.txt", sharedFileName("C:\\folder\\file.txt", "fallback"))
        assertEquals("fallback", sharedFileName("..", "fallback"))
        assertEquals("fallback", sharedFileName(null, "fallback"))
        assertFalse(sharedFileName("bad\nname", "fallback").contains('\n'))
    }
    @Test fun collisionsKeepBothFilesAndExtensions() {
        val root = temporary.newFolder()
        root.resolve("file.txt").writeText("keep")
        root.resolve("file (1).txt").writeText("keep too")
        assertEquals("file (2).txt", availableSharedName(root, "file.txt"))
        assertEquals("keep", root.resolve("file.txt").readText())
        root.resolve(".hidden").mkdir()
        assertEquals(".hidden (1)", availableSharedName(root, ".hidden"))
    }
}
