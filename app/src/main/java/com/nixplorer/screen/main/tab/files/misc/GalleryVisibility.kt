package com.nixplorer.screen.main.tab.files.misc

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Only changes this folder's marker; never follows a marker symlink or removes a directory. */
internal object GalleryVisibility {
    fun isHidden(folder: File): Boolean {
        val marker = folder.toPath().resolve(".nomedia")
        return Files.exists(marker, NOFOLLOW_LINKS)
    }

    fun setHidden(folder: File, hidden: Boolean) {
        val directory = folder.canonicalFile.toPath()
        if (!Files.isDirectory(directory)) throw IOException("Folder is no longer available")
        val marker = directory.resolve(".nomedia")
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
                throw IOException(".nomedia is not a regular file")
            }
            if (!hidden) Files.delete(marker)
        } else if (hidden) {
            // CREATE_NEW preserves existing content if another operation creates the marker.
            Files.createFile(marker)
        }
    }
}
