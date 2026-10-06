package com.nixplorer.screen.main.tab.files.misc

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class FolderSummary(val bytes: Long, val lastUpdated: Long, val complete: Boolean, val fileCount: Long = 0)

private val folderScans = Semaphore(2)

internal suspend fun File.folderSummary(): FolderSummary = withContext(Dispatchers.IO) {
    folderScans.withPermit {
        var bytes = 0L
        var fileCount = 0L
        var updated = lastModified()
        var complete = true
        val pending = ArrayDeque<File>()
        pending.add(this@folderSummary)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val entry = pending.removeLast()
            // Don't follow links outside the folder or into cycles.
            if (Files.isSymbolicLink(entry.toPath())) continue
            updated = maxOf(updated, entry.lastModified())
            if (entry.isDirectory) {
                val children = entry.listFiles()
                if (children == null) complete = false else pending.addAll(children)
            } else if (entry.isFile) {
                bytes += entry.length()
                fileCount++
            } else complete = false
        }
        FolderSummary(bytes, updated, complete, fileCount)
    }
}
