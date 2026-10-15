package com.nixplorer.screen.main.tab.files.task

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException

/** Local filesystem operations. All staging files are created exclusively and owned by one operation. */
object SafeFileOperations {
    const val MAX_ITEMS = 100_000
    const val MAX_DEPTH = 256
    const val MAX_PLAN_PATH_CHARACTERS = 8L * 1024 * 1024
    fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Operation cancelled")
    }

    fun relativePath(name: String): String {
        val path = name.replace('\\', '/')
        require(path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
            "Absolute or empty archive path: $name"
        }
        val parts = path.trimEnd('/').split('/')
        require(parts.size <= MAX_DEPTH && parts.all { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it && ':' !in it }) {
            "Unsafe path: $name"
        }
        return parts.joinToString("/")
    }

    fun contained(root: File, relative: String): File {
        val safe = relativePath(relative)
        require(!Files.isSymbolicLink(root.toPath())) { "Symbolic link destination root: $root" }
        // Android's /data/user/0 and /sdcard ancestors may be system aliases. Resolve the selected root once;
        // reject links inside that root instead of treating Android's fixed aliases as malicious entries.
        val base = root.canonicalFile.toPath()
        val target = base.resolve(safe).normalize()
        require(target.startsWith(base) && target != base) { "Path escapes destination: $relative" }
        // Reject links even when they currently resolve inside the destination.
        var ancestor: java.nio.file.Path? = target
        while (ancestor != null && ancestor.startsWith(base)) {
            require(!Files.isSymbolicLink(ancestor)) { "Symbolic link in destination: $ancestor" }
            ancestor = ancestor.parent
        }
        require(target.toFile().canonicalFile.toPath().startsWith(base)) { "Path escapes destination: $relative" }
        return target.toFile()
    }

    fun directory(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic link directory: $file" }
        if (!file.isDirectory && !file.mkdirs()) throw IOException("Cannot create directory: $file. Check access and free space.")
        if (!file.isDirectory) throw IOException("Destination is not a directory: $file")
    }

    data class Identity(val key: String?, val bytes: Long, val modified: Long, val digest: String)
    fun directoryIdentity(file: File): Identity {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val b = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(a.isDirectory && b.isDirectory && a.fileKey() == b.fileKey()) { "Directory changed: $file" }
        return Identity(a.fileKey()?.toString()?.takeIf { it == b.fileKey()?.toString() }, 0, 0, "directory")
    }
    fun identity(file: File, cancelled: () -> Boolean = { false }): Identity {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(a.isRegularFile) { "Not a regular file: $file" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled(cancelled)
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val b = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(a.fileKey() == b.fileKey() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime()) { "Source changed while reading: $file" }
        // UnixFileKey has a stable dev/inode representation; some Windows providers expose only object.toString().
        val key = a.fileKey()?.toString()?.takeIf { it == b.fileKey()?.toString() }
        return Identity(key, a.size(), a.lastModifiedTime().toMillis(), digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** No recursive cleanup: a recreated source, changed content or unverified destination is retained. */
    fun deleteVerifiedSource(source: File, destination: File, expected: Identity, cancelled: () -> Boolean = { false }, beforeQuarantine: () -> Unit = {}) {
        checkCancelled(cancelled)
        if (expected.digest == "directory") {
            require(expected.key != null && directoryIdentity(source) == expected && destination.isDirectory) { "Source directory changed or destination unavailable; retained: $source" }
            Files.newDirectoryStream(source.toPath()).use { require(!it.iterator().hasNext()) { "Source directory contains files; retained: $source" } }
            val retained = File.createTempFile(".nix-move-source-", ".retained", source.parentFile).apply { delete() }
            val intent = File.createTempFile(".nix-source-", ".properties", source.parentFile)
            intent.outputStream().use { out -> Properties().apply {
                setProperty("source", source.name); setProperty("retained", retained.name); setProperty("digest", "directory")
            }.store(out, "Retained empty-directory move"); out.fd.sync() }
            beforeQuarantine()
            try {
                Files.move(source.toPath(), retained.toPath(), ATOMIC_MOVE)
                require(directoryIdentity(retained) == expected) { "Directory was recreated; retained at $retained" }
                checkCancelled(cancelled)
                Files.delete(retained.toPath()) // Fails safely if a new file was added, even after the emptiness check.
                intent.delete()
            } catch (e: Exception) {
                if (retained.exists() && !source.exists()) {
                    try { Files.move(retained.toPath(), source.toPath()); intent.delete() } catch (restore: Exception) { e.addSuppressed(restore) }
                }
                throw e
            }
            return
        }
        require(expected.key != null && identity(source, cancelled) == expected) { "Source changed; retained: $source" }
        val copied = identity(destination, cancelled)
        require(copied.bytes == expected.bytes && copied.digest == expected.digest) { "Destination changed; source retained: $source" }
        checkCancelled(cancelled)
        // Recheck the inode immediately before unlinking; never delete a missing/recreated path after a move.
        val current = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(current.fileKey()?.toString() == expected.key && current.size() == expected.bytes && current.lastModifiedTime().toMillis() == expected.modified) { "Source changed; retained: $source" }
        // Rename to an exclusively reserved name, then verify THAT inode before unlinking.
        // A replacement at the original path can never be unlinked by subsequent cleanup.
        val quarantine = File.createTempFile(".nix-move-source-", ".retained", source.parentFile)
        quarantine.delete()
        val intent = File.createTempFile(".nix-source-", ".properties", source.parentFile)
        intent.outputStream().use { output ->
            Properties().apply {
                setProperty("source", source.name); setProperty("retained", quarantine.name)
                setProperty("digest", expected.digest); setProperty("bytes", expected.bytes.toString())
            }.store(output, "Verify retained source before manual recovery"); output.fd.sync()
        }
        beforeQuarantine()
        try {
            Files.move(source.toPath(), quarantine.toPath(), ATOMIC_MOVE)
            require(identity(quarantine, cancelled) == expected) { "Source was recreated; retained at $quarantine" }
            checkCancelled(cancelled)
            Files.delete(quarantine.toPath())
            intent.delete()
        } catch (e: Exception) {
            if (quarantine.exists() && !source.exists()) {
                try { Files.move(quarantine.toPath(), source.toPath()); intent.delete() }
                catch (restore: Exception) { e.addSuppressed(restore) }
            }
            if (e is CancellationException) throw e
            throw IOException("Source retained at ${if (source.exists()) source else quarantine}: ${e.message}", e)
        }
    }

    fun stagedWrite(root: File, relative: String, overwrite: Boolean, cancelled: () -> Boolean = { false },
                    maxBytes: Long = Long.MAX_VALUE, writer: (File) -> Unit): File {
        val destination = contained(root, relative)
        directory(destination.parentFile!!)
        contained(root, relative)
        val stage = File.createTempFile(".nix-stage-", ".part", destination.parentFile)
        try {
            writer(stage)
            checkCancelled(cancelled)
            require(stage.length() <= maxBytes) { "File exceeds operation limit" }
            contained(root, relative) // Recheck directly before publication.
            replace(stage, destination, overwrite)
            return destination
        } finally { stage.delete() }
    }

    /** Descriptor-relative extraction resists a destination directory being replaced by a symlink mid-write. */
    fun stagedStream(root: File, relative: String, overwrite: Boolean, maxBytes: Long,
                     cancelled: () -> Boolean = { false }, expectedBytes: Long? = null, expectedDigest: String? = null,
                     beforePublish: () -> Unit = {}, operationId: String? = null,
                     onProgress: (Long) -> Unit = {},
                     writeChannel: (java.nio.channels.SeekableByteChannel, java.nio.ByteBuffer) -> Int = { channel, bytes -> channel.write(bytes) },
                     input: () -> InputStream): Long {
        val destination = contained(root, relative)
        directory(destination.parentFile!!)
        val base = root.canonicalFile.toPath()
        val rootIdentity = Files.readAttributes(base, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        val opened = Files.newDirectoryStream(base)
        if (opened !is java.nio.file.SecureDirectoryStream<java.nio.file.Path>) {
            opened.close()
            // Do not silently fall back to racy path-based archive extraction on unsupported filesystems.
            throw IOException("This filesystem does not support secure staged writes. Choose local Android storage.")
        }
        val handles = mutableListOf(opened)
        var parent: java.nio.file.SecureDirectoryStream<java.nio.file.Path> = opened
        require(operationId == null || operationId.matches(Regex("[A-Za-z0-9-]+")))
        val name = java.nio.file.Paths.get(".nix-stage-${operationId?.plus("-") ?: ""}${java.util.UUID.randomUUID()}.part")
        var stageCreated = false
        try {
            require(opened.getFileAttributeView(java.nio.file.attribute.BasicFileAttributeView::class.java).readAttributes().fileKey() == rootIdentity) { "Destination root changed" }
            val parts = relativePath(relative).split('/')
            for (part in parts.dropLast(1)) {
                parent = parent.newDirectoryStream(java.nio.file.Paths.get(part), NOFOLLOW_LINKS)
                handles.add(parent)
            }
            val target = java.nio.file.Paths.get(parts.last())
            fun validateTarget() {
                require(Files.readAttributes(base, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == rootIdentity) { "Destination root changed" }
                contained(root, relative)
                val attributes = try { parent.getFileAttributeView(target, java.nio.file.attribute.BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes() }
                    catch (_: java.nio.file.NoSuchFileException) { null }
                require(attributes == null || overwrite) { "Destination already exists" }
                require(attributes == null || attributes.isRegularFile) { "Destination is a directory or symbolic link" }
            }
            validateTarget()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            parent.newByteChannel(name, setOf(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS)).use { channel ->
                stageCreated = true
                input().use { from ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled(cancelled)
                        val n = from.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("File/extraction limit exceeded ($maxBytes bytes)")
                        digest.update(buffer, 0, n)
                        val bytes = java.nio.ByteBuffer.wrap(buffer, 0, n)
                        while (bytes.hasRemaining()) { checkCancelled(cancelled); writeChannel(channel, bytes) }
                        onProgress(total)
                    }
                }
                if (channel is java.nio.channels.FileChannel) channel.force(true)
            }
            require(expectedBytes == null || total == expectedBytes) { "Incomplete file/archive entry" }
            require(expectedDigest == null || digest.digest().joinToString("") { "%02x".format(it) } == expectedDigest) { "Copied file checksum differs from source" }
            beforePublish()
            checkCancelled(cancelled)
            validateTarget()
            // SecureDirectoryStream.move uses the open parent descriptor; directory-name races cannot redirect it.
            parent.move(name, parent, target)
            stageCreated = false
            return total
        } finally {
            if (stageCreated) runCatching { parent.deleteFile(name) }
            handles.asReversed().forEach { runCatching { it.close() } }
        }
    }

    fun stream(input: InputStream, stage: File, maxBytes: Long, cancelled: () -> Boolean = { false }): Long {
        var total = 0L
        java.io.FileOutputStream(stage).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled(cancelled)
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("Extraction limit exceeded ($maxBytes bytes)")
                output.write(buffer, 0, n)
            }
            output.fd.sync()
        }
        return total
    }

    /** A durable intent records the backup before any rename. Failed replacement retains recovery material. */
    fun replace(stage: File, destination: File, overwrite: Boolean = true,
                move: (File, File, Boolean) -> Unit = { from, to, atomic ->
                    if (atomic) Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    else Files.move(from.toPath(), to.toPath())
                }) {
        require(stage.parentFile.canonicalFile == destination.parentFile!!.canonicalFile) { "Stage must be beside destination" }
        require(stage.isFile && !Files.isSymbolicLink(stage.toPath())) { "Invalid replacement stage" }
        java.io.FileOutputStream(stage, true).use { it.fd.sync() }
        require(!Files.isSymbolicLink(destination.toPath())) { "Symbolic link destination" }
        if (!overwrite && destination.exists()) throw IOException("Destination already exists: $destination")
        if (!destination.exists()) { move(stage, destination, false); return }
        require(destination.isFile) { "Cannot replace a directory: $destination" }
        try { move(stage, destination, true); return } catch (_: AtomicMoveNotSupportedException) { /* Recoverable fallback */ }
        val backup = File.createTempFile(".nix-backup-", ".bak", destination.parentFile)
        backup.delete()
        val intent = File.createTempFile(".nix-replace-", ".properties", destination.parentFile)
        val originalIdentity = identity(destination)
        val record = Properties().apply {
            setProperty("destination", destination.name); setProperty("stage", stage.name); setProperty("backup", backup.name)
            setProperty("digest", originalIdentity.digest); setProperty("bytes", originalIdentity.bytes.toString())
        }
        intent.outputStream().use { record.store(it, "NiXplorer recoverable replacement"); it.fd.sync() }
        try {
            move(destination, backup, false)
            try { move(stage, destination, false) } catch (failure: Exception) {
                try { move(backup, destination, false) } catch (restore: Exception) {
                    failure.addSuppressed(restore)
                    throw IOException("Replacement interrupted. Original is recoverable at $backup; replacement at $stage; record $intent", failure)
                }
                throw failure
            }
            backup.delete(); intent.delete()
        } catch (failure: Exception) {
            // Keep intent/backup for reconciliation. No failure path removes the only surviving copy.
            throw failure
        }
    }

    /** Reconcile only an exact destination recorded in a task checkpoint. Never replay source deletion. */
    fun reconcileReplacement(destination: File): List<String> {
        val notes = mutableListOf<String>()
        val directory = destination.parentFile ?: return notes
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return notes
        Files.newDirectoryStream(directory.toPath(), ".nix-replace-*.properties").use { records ->
            for (path in records) {
                try {
                    val properties = Properties().apply { path.toFile().inputStream().use { load(it) } }
                    if (properties.getProperty("destination") != destination.name) continue
                    val backupName = properties.getProperty("backup") ?: continue
                    require(backupName.startsWith(".nix-backup-") && backupName.endsWith(".bak"))
                    val backup = contained(directory, backupName)
                    if (!destination.exists() && backup.isFile) {
                        val identity = identity(backup)
                        require(identity.digest == properties.getProperty("digest") && identity.bytes.toString() == properties.getProperty("bytes")) { "Backup differs from checkpoint" }
                        contained(directory, destination.name)
                        Files.move(backup.toPath(), destination.toPath())
                        notes.add("Restored original: $destination. Replacement and intent retained for review at $path")
                    } else notes.add("Review replacement record $path; destination was retained without replaying operations")
                } catch (e: Exception) { notes.add("Recovery needs review at $path: ${e.message}") }
            }
        }
        return notes
    }

    fun reconcileRetainedSource(source: File, expected: Identity?): List<String> {
        if (expected == null) return emptyList()
        val directory = source.parentFile ?: return emptyList()
        if (!directory.isDirectory) return listOf("Source storage is unavailable: $source")
        val notes = mutableListOf<String>()
        Files.newDirectoryStream(directory.toPath(), ".nix-source-*.properties").use { records ->
            for (path in records) {
                try {
                    val p = Properties().apply { path.toFile().inputStream().use { load(it) } }
                    if (p.getProperty("source") != source.name) continue
                    val name = p.getProperty("retained") ?: continue
                    require(name.startsWith(".nix-move-source-") && name.endsWith(".retained"))
                    val retained = contained(directory, name)
                    val matches = if (expected.digest == "directory") retained.isDirectory && directoryIdentity(retained) == expected
                        else retained.isFile && identity(retained) == expected
                    if (!source.exists() && matches) {
                        contained(directory, source.name)
                        Files.move(retained.toPath(), source.toPath())
                        notes.add("Restored retained source: $source. No source deletion was replayed.")
                    } else notes.add("Review retained source $retained and record $path; nothing was deleted")
                } catch (e: Exception) { notes.add("Retained source needs review at $path: ${e.message}") }
            }
        }
        return notes
    }

    fun validateCopyPaths(sources: List<File>, destination: File) {
        val dest = destination.canonicalFile.toPath()
        val paths = sources.map { it.canonicalFile.toPath() }
        paths.forEachIndexed { index, source ->
            require(source.parent != dest && source != dest && !dest.startsWith(source)) { "Cannot copy/move a path into itself or its descendant: $source" }
            require(paths.indices.none { it != index && (source.startsWith(paths[it]) || paths[it].startsWith(source)) }) { "Overlapping sources: $source" }
        }
    }

    /** Cancellable deletion through directory handles. Cancellation leaves remaining entries for review. */
    fun deleteTree(root: File, operationId: String, cancelled: () -> Boolean = { false },
                   onPrepared: (String, String) -> Unit = { _, _ -> }, onDeleted: (String) -> Unit = {}) {
        require(operationId.matches(Regex("[A-Za-z0-9-]+")))
        val directory = root.parentFile!!.canonicalFile
        val stream = Files.newDirectoryStream(directory.toPath())
        if (stream !is java.nio.file.SecureDirectoryStream<java.nio.file.Path>) {
            stream.close(); throw IOException("Secure deletion is unsupported by this filesystem")
        }
        val secured: java.nio.file.SecureDirectoryStream<java.nio.file.Path> = stream
        var count = 0
        fun remove(parent: java.nio.file.SecureDirectoryStream<java.nio.file.Path>, name: java.nio.file.Path, logical: File, depth: Int) {
            checkCancelled(cancelled)
            require(++count <= MAX_ITEMS && depth <= MAX_DEPTH) { "Deletion limit exceeded; remaining content retained" }
            val attributes = parent.getFileAttributeView(name, java.nio.file.attribute.BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            require(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile) && attributes.fileKey() != null) { "Unsupported link/special file or unverifiable source: $name" }
            if (attributes.isDirectory) parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { children ->
                require(children.getFileAttributeView(java.nio.file.attribute.BasicFileAttributeView::class.java).readAttributes().fileKey() == attributes.fileKey()) { "Directory changed: $name" }
                for (child in children) remove(children, child.fileName, File(logical, child.fileName.toString()), depth + 1)
            }
            checkCancelled(cancelled)
            val retained = java.nio.file.Paths.get(".nix-delete-$operationId-${java.util.UUID.randomUUID()}")
            onPrepared(logical.path, File(logical.parentFile, retained.toString()).path)
            parent.move(name, parent, retained)
            try {
                val moved = parent.getFileAttributeView(retained, java.nio.file.attribute.BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
                require(moved.fileKey() == attributes.fileKey()) { "Source recreated; retained as $retained" }
                checkCancelled(cancelled)
                if (attributes.isDirectory) parent.deleteDirectory(retained) else parent.deleteFile(retained)
                onDeleted(logical.path)
            } catch (e: Exception) {
                // Descriptor-relative move can replace an existing target. Never restore over a concurrently
                // recreated source name; keep the journaled retained entry for explicit review instead.
                if (e is CancellationException) throw e
                throw IOException("Entry retained at ${File(logical.parentFile, retained.toString())}; review task details: ${e.message}", e)
            }
        }
        secured.use { remove(it, root.toPath().fileName, root, 0) }
    }

    /** Depth-first directory streams bound memory and close handles even on cancellation/error. */
    fun walk(root: File, cancelled: () -> Boolean = { false }, onDirectory: (File) -> Unit = {}, visitor: (File) -> Unit) {
        var count = 0
        var pathCharacters = 0L
        fun visit(file: File, depth: Int) {
            checkCancelled(cancelled)
            require(depth <= MAX_DEPTH && ++count <= MAX_ITEMS) { "Directory operation limit exceeded ($MAX_ITEMS items, $MAX_DEPTH levels)" }
            pathCharacters += file.path.length
            require(pathCharacters <= MAX_PLAN_PATH_CHARACTERS) { "Directory path metadata exceeds memory limit; select smaller folders" }
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Symbolic link is not supported: $file")
            if (file.isFile) { visitor(file); return }
            if (!file.exists()) throw IOException("Storage disconnected or path no longer exists: $file")
            if (!file.canRead()) throw IOException("Permission denied: $file")
            onDirectory(file)
            var empty = true
            Files.newDirectoryStream(file.toPath()).use { entries ->
                for (child in entries) { empty = false; visit(child.toFile(), depth + 1) }
            }
            if (empty) visitor(file)
        }
        visit(root, 0)
    }
}
