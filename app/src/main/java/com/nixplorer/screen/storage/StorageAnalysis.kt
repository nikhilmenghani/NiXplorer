package com.nixplorer.screen.storage

import java.io.File
import java.nio.file.Files
import java.util.PriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class StorageEntry(val file: File, val bytes: Long, val complete: Boolean = true)
internal data class FileStorageReport(val files: List<StorageEntry>, val folders: List<StorageEntry>, val bytes: Long, val fileCount: Long, val skippedFolders: Int)
private data class FolderFrame(val file: File, val children: Array<File>, var index: Int = 0, var bytes: Long = 0, var complete: Boolean = true)

internal suspend fun analyzeFiles(root: File, limit: Int = 100, onProgress: (Long) -> Unit = {}): FileStorageReport = withContext(Dispatchers.IO) {
    require(limit > 0)
    val files = PriorityQueue<StorageEntry>(compareBy { it.bytes })
    val folders = PriorityQueue<StorageEntry>(compareBy { it.bytes })
    fun keep(queue: PriorityQueue<StorageEntry>, entry: StorageEntry) {
        if (entry.bytes == 0L) return
        queue.add(entry)
        if (queue.size > limit) queue.poll()
    }
    var count = 0L
    var skipped = 0
    val frames = ArrayDeque<FolderFrame>()
    fun frame(file: File): FolderFrame {
        val children = file.listFiles()
        if (children == null) skipped++
        return FolderFrame(file, children ?: emptyArray(), complete = children != null)
    }
    frames.add(frame(root))
    var total = 0L
    var lastUpdate = 0L
    while (frames.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val parent = frames.last()
        if (parent.index < parent.children.size) {
            val child = parent.children[parent.index++]
            if (Files.isSymbolicLink(child.toPath())) continue
            if (child.isDirectory) frames.add(frame(child))
            else if (child.isFile) {
                val bytes = child.length()
                parent.bytes += bytes
                count++
                keep(files, StorageEntry(child, bytes))
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 200) { onProgress(count); lastUpdate = now }
            } else parent.complete = false
        } else {
            frames.removeLast()
            if (frames.isEmpty()) total = parent.bytes
            else {
                keep(folders, StorageEntry(parent.file, parent.bytes, parent.complete))
                frames.last().bytes += parent.bytes
                frames.last().complete = frames.last().complete && parent.complete
            }
        }
    }
    FileStorageReport(files.sortedByDescending { it.bytes }, folders.sortedByDescending { it.bytes }, total, count, skipped)
}
