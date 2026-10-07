package com.nixplorer.screen.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class EmptyFolder(val file: File, val folderCount: Int)
internal data class EmptyFolderReport(val folders: List<EmptyFolder>, val checked: Long, val skipped: Int)
internal data class EmptyFolderCleanup(val removed: Int, val skipped: Int)

/** deleteDirectory uses rmdir semantics, so replacing a folder with a file cannot unlink it. */
internal fun removeEmptyDirectory(file: File): Boolean = runCatching {
    val path = file.toPath()
    Files.newDirectoryStream(path.parent).use { stream ->
        if (stream is SecureDirectoryStream<Path>) {
            stream.deleteDirectory(path.fileName)
            true
        } else {
            // Android providers without secure directory streams still have directory-only rmdir.
            val process = ProcessBuilder("/system/bin/rmdir", file.absolutePath).redirectErrorStream(true).start()
            process.inputStream.use { it.readBytes() }
            process.waitFor() == 0
        }
    }
}.getOrDefault(false)
private data class EmptyFrame(val file: File, val children: Array<File>, var index: Int = 0,
    var empty: Boolean = true, var count: Int = 1, val candidates: MutableList<EmptyFolder> = mutableListOf())

/** Only the highest empty subtree is offered; inaccessible entries and links keep ancestors. */
internal suspend fun findEmptyFolders(root: File, onProgress: (Long) -> Unit = {}): EmptyFolderReport = withContext(Dispatchers.IO) {
    var checked = 0L
    var skipped = 0
    var lastUpdate = 0L
    fun frame(file: File): EmptyFrame {
        checked++
        val children = file.listFiles()
        if (children == null) skipped++
        return EmptyFrame(file, children ?: emptyArray(), empty = children != null)
    }
    require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
    val stack = ArrayDeque<EmptyFrame>()
    stack.add(frame(root))
    var result = emptyList<EmptyFolder>()
    while (stack.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val parent = stack.last()
        if (parent.index < parent.children.size) {
            val child = parent.children[parent.index++]
            if (Files.isSymbolicLink(child.toPath())) parent.empty = false
            else if (child.isDirectory) stack.add(frame(child))
            else parent.empty = false // Includes hidden and zero-byte files, and unknown entries.
        } else {
            stack.removeLast()
            val candidates = if (parent.empty && parent.file != root) listOf(EmptyFolder(parent.file, parent.count)) else parent.candidates
            if (stack.isEmpty()) result = candidates
            else {
                val ancestor = stack.last()
                ancestor.empty = ancestor.empty && parent.empty
                ancestor.count += parent.count
                ancestor.candidates.addAll(candidates)
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastUpdate >= 200) { onProgress(checked); lastUpdate = now }
    }
    EmptyFolderReport(result.sortedBy { it.file.absolutePath.lowercase() }, checked, skipped)
}

/** Recheck the entire subtree first. Caller must supply directory-only rmdir, never file unlink. */
internal suspend fun cleanEmptyFolders(root: File, folders: List<EmptyFolder>,
    removeDirectory: (File) -> Boolean, onProgress: (Int) -> Unit = {}): EmptyFolderCleanup = withContext(Dispatchers.IO) {
    val rootPath = root.canonicalFile.toPath()
    var removed = 0
    var skipped = 0
    for ((index, item) in folders.withIndex()) {
        currentCoroutineContext().ensureActive()
        val target = item.file
        val path = target.toPath().toAbsolutePath().normalize()
        var allowed = path != rootPath && path.startsWith(rootPath)
        var ancestor: File? = target
        while (allowed && ancestor != null && ancestor.toPath().toAbsolutePath().normalize() != rootPath) {
            if (Files.isSymbolicLink(ancestor.toPath())) allowed = false
            ancestor = ancestor.parentFile
        }
        val pending = ArrayDeque<File>()
        val directories = mutableListOf<File>()
        if (allowed) pending.add(target)
        while (allowed && pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeLast()
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) { allowed = false; break }
            val children = directory.listFiles()
            if (children == null) { allowed = false; break }
            if (children.any { !it.isDirectory || Files.isSymbolicLink(it.toPath()) }) { allowed = false; break }
            directories.add(directory)
            pending.addAll(children)
        }
        if (allowed) {
            for (directory in directories.asReversed()) {
                currentCoroutineContext().ensureActive()
                // rmdir atomically rejects a directory if a file appears after this check.
                if (Files.isSymbolicLink(directory.toPath()) || directory.listFiles()?.isEmpty() != true || !removeDirectory(directory)) {
                    allowed = false
                } else removed++
            }
        }
        if (!allowed) skipped++
        onProgress(index + 1)
    }
    EmptyFolderCleanup(removed, skipped)
}
