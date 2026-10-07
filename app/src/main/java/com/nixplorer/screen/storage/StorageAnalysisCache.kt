package com.nixplorer.screen.storage

import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class StorageStamp(val file: File, val modified: Long, val length: Long, val directory: Boolean, val readable: Boolean) : Serializable {
    fun matches(): Boolean = file.exists() && file.isDirectory == directory &&
        file.lastModified() == modified && (directory || file.length() == length) && file.canRead() == readable
    companion object {
        fun capture(file: File) = StorageStamp(file, file.lastModified(), file.length(), file.isDirectory, file.canRead())
    }
}

private data class CachedStorage(val schema: Int, val root: String, val report: FileStorageReport, val stamps: List<StorageStamp>) : Serializable

/** Validate metadata instead of enumerating directories and rebuilding totals on every visit. */
internal object StorageAnalysisCache {
    private val mutex = Mutex()
    private val memory = java.util.concurrent.ConcurrentHashMap<String, CachedStorage>()
    fun peek(root: File): FileStorageReport? = memory[root.absolutePath]?.report
    suspend fun load(root: File, cacheDir: File, force: Boolean = false,
        onCached: (FileStorageReport) -> Unit = {}, onScanning: () -> Unit = {}, onProgress: (Long) -> Unit = {}): FileStorageReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cacheFile = File(cacheDir, "storage-analysis-${root.absolutePath.hashCode()}.bin")
            val cached = memory[root.absolutePath] ?: runCatching {
                ObjectInputStream(cacheFile.inputStream().buffered()).use { it.readObject() as CachedStorage }
            }.getOrNull()
            if (cached != null && cached.schema == 2 && cached.root == root.absolutePath) {
                memory[root.absolutePath] = cached
                onCached(cached.report)
                var valid = true
                for (stamp in cached.stamps) {
                    currentCoroutineContext().ensureActive()
                    if (!stamp.matches()) { valid = false; break }
                }
                if (valid && !force) return@withLock cached.report
            }
            onScanning()
            val stamps = mutableListOf<StorageStamp>()
            val report = analyzeFiles(root, onSnapshot = { stamps.add(StorageStamp.capture(it)) }, onProgress = onProgress)
            currentCoroutineContext().ensureActive()
            val snapshot = CachedStorage(2, root.absolutePath, report, stamps)
            memory[root.absolutePath] = snapshot
            // An interrupted scan never replaces the previous complete report.
            runCatching {
                cacheDir.mkdirs()
                val temporary = File(cacheDir, cacheFile.name + ".tmp")
                ObjectOutputStream(temporary.outputStream().buffered()).use {
                    it.writeObject(snapshot)
                }
                if (!temporary.renameTo(cacheFile)) temporary.delete()
            }
            report
        }
    }
}
