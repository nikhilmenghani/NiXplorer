package com.nixplorer.screen.main.tab.files.misc

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryVisibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun hideThenShowPreservesOtherFiles() {
        val folder = temporary.newFolder()
        val photo = folder.resolve("photo.jpg").apply { writeText("photo") }
        assertFalse(GalleryVisibility.isHidden(folder))
        GalleryVisibility.setHidden(folder, true)
        assertTrue(GalleryVisibility.isHidden(folder))
        folder.resolve(".nomedia").writeText("existing marker")
        GalleryVisibility.setHidden(folder, true)
        assertEquals("existing marker", folder.resolve(".nomedia").readText())
        GalleryVisibility.setHidden(folder, false)
        assertFalse(GalleryVisibility.isHidden(folder))
        assertEquals("photo", photo.readText())
    }

    @Test fun refusesDirectoryMarkerWithoutDeletingContents() {
        val folder = temporary.newFolder()
        val marker = folder.resolve(".nomedia").apply { mkdir() }
        marker.resolve("keep").writeText("keep")
        assertThrows(java.io.IOException::class.java) { GalleryVisibility.setHidden(folder, false) }
        assertTrue(marker.resolve("keep").exists())
    }

    @Test fun missingFolderDoesNotCreateAnything() {
        val folder = temporary.root.resolve("missing")
        assertThrows(java.io.IOException::class.java) { GalleryVisibility.setHidden(folder, true) }
        assertFalse(folder.exists())
    }

    @Test fun showingChildDoesNotRemoveParentMarker() {
        val parent = temporary.newFolder()
        val child = parent.resolve("child").apply { mkdir() }
        GalleryVisibility.setHidden(parent, true)
        GalleryVisibility.setHidden(child, true)
        GalleryVisibility.setHidden(child, false)
        assertTrue(GalleryVisibility.isHidden(parent))
        assertFalse(GalleryVisibility.isHidden(child))
    }
}
