package com.nixplorer.screen.archive

import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry

internal fun safeArchivePath(path: String): String {
    val normalized = path.replace('\\', '/')
    require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "Absolute archive path" }
    val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    require(parts.none { it == ".." || '\u0000' in it }) { "Unsafe archive path" }
    return parts.joinToString("/")
}

/** Normalize read-only formats into a disposable ZIP for the existing browsing/extraction pipeline. */
internal suspend fun convertArchiveToZip(source: File): File {
    val output = File(source.parentFile, source.name + ".zip")
    val reader = Archive.readNew()
    try {
        Archive.readSupportFilterAll(reader)
        Archive.readSupportFormatAll(reader)
        // A raw compressed stream (e.g. .gz) is exposed as one decompressed file.
        Archive.readSupportFormatRaw(reader)
        Archive.readOpenFileName(reader, source.absolutePath.toByteArray(Charsets.UTF_8), 64 * 1024L)
        val seen = hashSetOf<String>()
        val buffer = ByteBuffer.allocateDirect(64 * 1024)
        val bytes = ByteArray(buffer.capacity())
        val budget = (source.parentFile!!.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0)
        var written = 0L
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.setLevel(0)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = Archive.readNextHeader(reader)
                if (entry == 0L) break
                require(Archive.format(reader) != Archive.FORMAT_RAW || Archive.filterCode(reader, 0) != Archive.FILTER_NONE) { "Not a supported archive" }
                require(!ArchiveEntry.isEncrypted(entry)) { "Encrypted archives require a password" }
                val directory = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR
                // Never reproduce symbolic links, hard links or special filesystem entries.
                require(directory || (ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && !ArchiveEntry.hardlinkIsSet(entry))) { "Archive contains unsupported links or special entries" }
                val raw = if (Archive.format(reader) == Archive.FORMAT_RAW) source.name.substringBeforeLast('.', source.name) else
                    ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8).orEmpty()
                val path = safeArchivePath(raw)
                if (path.isEmpty()) { Archive.readDataSkip(reader); continue }
                val name = path + if (directory) "/" else ""
                require(seen.add(name)) { "Duplicate archive path" }
                zip.putNextEntry(ZipEntry(name).apply {
                    if (ArchiveEntry.mtimeIsSet(entry)) time = ArchiveEntry.mtime(entry) * 1000
                })
                if (!directory) while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    Archive.readData(reader, buffer)
                    val count = buffer.position()
                    if (count == 0) break
                    written += count
                    require(written <= budget) { "Not enough temporary storage to open archive" }
                    buffer.flip()
                    buffer.get(bytes, 0, count)
                    zip.write(bytes, 0, count)
                }
                zip.closeEntry()
            }
        }
        return output
    } catch (e: Exception) {
        output.delete()
        throw e
    } finally { Archive.readFree(reader) }
}
