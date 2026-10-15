package com.nixplorer.screen.main.tab.files.zip

import com.nixplorer.screen.main.tab.files.task.SafeFileOperations
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipFile

object ArchiveSafety {
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024
    const val MAX_RATIO = 1000L

    class Paths {
        private val entries = HashMap<String, Boolean>()
        private val directories = HashSet<String>()
        private var pathCharacters = 0L
        fun add(raw: String, directory: Boolean): String {
            val path = SafeFileOperations.relativePath(raw)
            pathCharacters += path.length
            require(pathCharacters <= 16L * 1024 * 1024) { "Archive path metadata exceeds 16 MiB limit" }
            require(entries.size < SafeFileOperations.MAX_ITEMS) { "Too many archive entries" }
            require(!entries.containsKey(path)) { "Duplicate archive entry: $raw" }
            require(directory || path !in directories) { "Conflicting archive file/directory: $raw" }
            val components = path.split('/')
            for (i in 1 until components.size) {
                val parent = components.take(i).joinToString("/")
                require(entries[parent] != false) { "Archive file used as directory: $parent" }
                directories.add(parent)
            }
            require(entries.size + directories.size < SafeFileOperations.MAX_ITEMS) { "Archive tree is too large" }
            entries[path] = directory
            if (directory) directories.add(path)
            return path
        }
    }

    fun validate(file: File, readData: Boolean = false, cancelled: () -> Boolean = { false }) {
        ZipFile(file).use { zip ->
            val paths = Paths()
            var total = 0L
            val entries = zip.entries()
            val buffer = ByteArray(64 * 1024)
            while (entries.hasMoreElements()) {
                SafeFileOperations.checkCancelled(cancelled)
                val entry = entries.nextElement()
                paths.add(entry.name, entry.isDirectory)
                require(entry.size >= 0 && entry.size <= MAX_ENTRY_BYTES) { "Archive entry too large: ${entry.name}" }
                total += entry.size
                require(total <= MAX_TOTAL_BYTES) { "Archive exceeds 32 GiB extraction limit" }
                require(entry.size < 1024 * 1024 || entry.size / maxOf(1, entry.compressedSize) <= MAX_RATIO) { "Suspicious compression ratio: ${entry.name}" }
                if (readData && !entry.isDirectory) {
                    var actual = 0L
                    val crc = CRC32()
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            SafeFileOperations.checkCancelled(cancelled)
                            val n = input.read(buffer)
                            if (n < 0) break
                            actual += n
                            if (actual > MAX_ENTRY_BYTES) throw IOException("Archive entry limit exceeded")
                            crc.update(buffer, 0, n)
                        }
                    }
                    require(actual == entry.size && crc.value == entry.crc) { "Archive checksum mismatch: ${entry.name}" }
                }
            }
        }
        // ZIP Unix symlink entries must not become paths in our browser/extractor.
        net.lingala.zip4j.ZipFile(file).use { zip ->
            zip.fileHeaders.forEach { header ->
                val attributes = header.externalFileAttributes
                if (attributes != null && attributes.size >= 4) {
                    val mode = ((attributes[3].toInt() and 255) shl 8) or (attributes[2].toInt() and 255)
                    require(mode and 0xf000 != 0xa000) { "Archive symlink is not supported: ${header.fileName}" }
                }
            }
        }
    }
}
