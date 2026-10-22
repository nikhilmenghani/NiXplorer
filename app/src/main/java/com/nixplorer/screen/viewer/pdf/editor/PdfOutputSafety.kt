package com.nixplorer.screen.viewer.pdf.editor

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.FileNotFoundException

/** Uri equality alone misses a file exposed through two different document providers. */
internal object PdfOutputSafety {
    fun requireDifferentFile(context: Context, source: Uri, destination: Uri) {
        require(source != destination) { "Choose a new output file; the source must be retained." }
        if (source.scheme == "file" && destination.scheme == "file") {
            require(File(requireNotNull(source.path)).canonicalFile != File(requireNotNull(destination.path)).canonicalFile) {
                "The output is the source file. Choose a new name."
            }
        }
        context.contentResolver.openFileDescriptor(source, "r")?.use { original ->
            val output = try {
                context.contentResolver.openFileDescriptor(destination, "r")
            } catch (_: FileNotFoundException) { null }
            output?.use { existing ->
                val left = Os.fstat(original.fileDescriptor)
                val right = Os.fstat(existing.fileDescriptor)
                require(left.st_ino == 0L || right.st_ino == 0L ||
                    left.st_dev != right.st_dev || left.st_ino != right.st_ino) {
                    "The output points to the original file. Choose a new name."
                }
            }
        }
    }
}
