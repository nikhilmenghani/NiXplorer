package com.nixplorer.screen.archive

import com.nixplorer.screen.main.tab.files.zip.ArchiveSafety
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import net.lingala.zip4j.ZipFile

internal class ArchivePasswordRequired : Exception()

internal fun isRar(source: File): Boolean = source.inputStream().use {
    val signature = ByteArray(6)
    it.read(signature) == 6 && signature.contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07))
}

internal suspend fun unlockRar(source: File, password: CharArray?): File {
    val output = File(source.parentFile, source.name + ".zip")
    val limits = ConversionLimits()
    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
    val options = com.github.junrar.ArchiveOptions.builder().maxDictionarySize(128L * 1024 * 1024)
        .apply { if (password != null) password(password) }.build()
    try {
        com.github.junrar.Archive(source, options).use { archive ->
            if (archive.isPasswordProtected && password == null) throw ArchivePasswordRequired()
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.setLevel(0)
                val seen = ArchiveSafety.Paths()
                archive.fileHeaders.forEach { header ->
                    currentCoroutineContext().ensureActive()
                    val path = safeArchivePath(header.fileName)
                    if (path.isNotEmpty()) {
                        val name = path + if (header.isDirectory) "/" else ""
                        seen.add(name, name.endsWith('/'))
                        limits.entry()
                        zip.putNextEntry(ZipEntry(name))
                        if (!header.isDirectory) archive.extractFile(header, object : java.io.OutputStream() {
                            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                                job?.ensureActive()
                                check(output.parentFile!!.usableSpace > 64L * 1024 * 1024) { "Not enough temporary storage" }
                                limits.bytes(count)
                                zip.write(bytes, offset, count)
                            }
                        })
                        zip.closeEntry()
                    }
                }
            }
        }
        ArchiveSafety.validate(output)
        return output
    } catch (e: Exception) {
        output.delete()
        val reason = e.javaClass.simpleName
        if (reason.contains("Password", true) || reason.contains("InitDecipherer", true) ||
            (password != null && reason.contains("Crc", true))) throw ArchivePasswordRequired()
        throw e
    }
}

internal fun isSevenZip(source: File): Boolean = source.inputStream().use {
    val signature = ByteArray(6)
    it.read(signature) == 6 && signature.contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c))
}

internal suspend fun unlockSevenZip(source: File, password: CharArray?): File {
    val output = File(source.parentFile, source.name + ".zip")
    val limits = ConversionLimits()
    try {
        org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(source)
            .setPassword(password).setMaxMemoryLimitKiB(128 * 1024).get().use { archive ->
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.setLevel(0)
                val seen = ArchiveSafety.Paths()
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = archive.nextEntry ?: break
                    val path = safeArchivePath(entry.name ?: "file")
                    if (path.isEmpty()) continue
                    val name = path + if (entry.isDirectory) "/" else ""
                    seen.add(name, name.endsWith('/'))
                    limits.entry()
                        zip.putNextEntry(ZipEntry(name))
                    if (!entry.isDirectory) while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = archive.read(bytes)
                        if (count < 0) break
                        check(output.parentFile!!.usableSpace > 64L * 1024 * 1024) { "Not enough temporary storage" }
                        limits.bytes(count)
                            zip.write(bytes, 0, count)
                    }
                    zip.closeEntry()
                }
            }
        }
        ArchiveSafety.validate(output)
        return output
    } catch (e: Exception) {
        output.delete()
        if (e is org.apache.commons.compress.PasswordRequiredException ||
            (password != null && e is java.io.IOException &&
                !e.message.orEmpty().contains("unsupported", true) &&
                !e.javaClass.simpleName.contains("MemoryLimit", true) &&
                listOf("space", "storage", "limit").none { e.message.orEmpty().contains(it, true) })) throw ArchivePasswordRequired()
        throw e
    }
}

internal suspend fun unlockZip(source: File, password: CharArray?): File {
    val input = ZipFile(source)
    if (!input.isEncrypted) return source
    if (password == null) throw ArchivePasswordRequired()
    input.setPassword(password)
    val output = File(source.parentFile, source.name + ".unlocked.zip")
    val limits = ConversionLimits()
    val seen = ArchiveSafety.Paths()
    try {
        input.use { archive -> ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.setLevel(0)
            archive.fileHeaders.forEach { header ->
                currentCoroutineContext().ensureActive()
                val path = safeArchivePath(header.fileName)
                if (path.isNotEmpty()) {
                    seen.add(path, header.isDirectory)
                    limits.entry()
                    zip.putNextEntry(ZipEntry(path + if (header.isDirectory) "/" else ""))
                    if (!header.isDirectory) archive.getInputStream(header).use { stream ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = stream.read(bytes)
                            if (count < 0) break
                            check(output.parentFile!!.usableSpace > 64L * 1024 * 1024) { "Not enough temporary storage" }
                            limits.bytes(count)
                            zip.write(bytes, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } }
        ArchiveSafety.validate(output)
        return output
    } catch (e: Exception) {
        output.delete()
        if (e is net.lingala.zip4j.exception.ZipException) throw ArchivePasswordRequired()
        throw e
    }
}

internal fun safeArchivePath(path: String): String {
    val normalized = path.replace('\\', '/')
    require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "Absolute archive path" }
    val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    require(parts.none { it == ".." || '\u0000' in it }) { "Unsafe archive path" }
    return parts.joinToString("/").also { if (it.isNotEmpty()) com.nixplorer.screen.main.tab.files.task.SafeFileOperations.relativePath(it) }
}

/** Normalize read-only formats into a disposable ZIP for the existing browsing/extraction pipeline. */
internal suspend fun convertArchiveToZip(source: File, password: CharArray? = null): File {
    val output = File(source.parentFile, source.name + ".zip")
    val limits = ConversionLimits()
    val reader = Archive.readNew()
    var requestedPassword = false
    var encrypted = false
    try {
        Archive.readSupportFilterAll(reader)
        Archive.readSupportFormatAll(reader)
        // A raw compressed stream (e.g. .gz) is exposed as one decompressed file.
        Archive.readSupportFormatRaw(reader)
        if (password != null) Archive.readAddPassphrase(reader, String(password).toByteArray(Charsets.UTF_8))
        Archive.readSetPassphraseCallback<Unit>(reader, Unit) { _, _ -> requestedPassword = true; null }
        Archive.readOpenFileName(reader, source.absolutePath.toByteArray(Charsets.UTF_8), 64 * 1024L)
        val seen = ArchiveSafety.Paths()
        val buffer = ByteBuffer.allocateDirect(64 * 1024)
        val bytes = ByteArray(buffer.capacity())
        val budget = minOf(ArchiveSafety.MAX_TOTAL_BYTES, (source.parentFile!!.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0))
        var written = 0L
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.setLevel(0)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = Archive.readNextHeader(reader)
                if (entry == 0L) break
                require(Archive.format(reader) != Archive.FORMAT_RAW || Archive.filterCode(reader, 0) != Archive.FILTER_NONE) { "Not a supported archive" }
                encrypted = encrypted || ArchiveEntry.isEncrypted(entry)
                if (encrypted && password == null) throw ArchivePasswordRequired()
                val directory = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFDIR
                // Never reproduce symbolic links, hard links or special filesystem entries.
                require(directory || (ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && !ArchiveEntry.hardlinkIsSet(entry))) { "Archive contains unsupported links or special entries" }
                val raw = if (Archive.format(reader) == Archive.FORMAT_RAW) source.name.substringBeforeLast('.', source.name) else
                    ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8).orEmpty()
                val path = safeArchivePath(raw)
                if (path.isEmpty()) { Archive.readDataSkip(reader); continue }
                val name = path + if (directory) "/" else ""
                seen.add(name, name.endsWith('/'))
                limits.entry()
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
                    limits.bytes(count)
                            zip.write(bytes, 0, count)
                }
                zip.closeEntry()
            }
        }
        ArchiveSafety.validate(output)
        return output
    } catch (e: Exception) {
        output.delete()
        if (e is me.zhanghai.android.libarchive.ArchiveException &&
            (requestedPassword || encrypted) && !e.message.orEmpty().contains("unsupported", true) &&
            !e.message.orEmpty().contains("not supported", true)) throw ArchivePasswordRequired()
        throw e
    } finally { Archive.readFree(reader) }
}

private class ConversionLimits {
    private var entries = 0
    private var total = 0L
    private var entryBytes = 0L
    fun entry() { require(++entries <= com.nixplorer.screen.main.tab.files.task.SafeFileOperations.MAX_ITEMS) { "Too many archive entries" }; entryBytes = 0 }
    fun bytes(count: Int) {
        entryBytes += count; total += count
        require(entryBytes <= ArchiveSafety.MAX_ENTRY_BYTES && total <= ArchiveSafety.MAX_TOTAL_BYTES) { "Archive extraction limit exceeded" }
    }
}
