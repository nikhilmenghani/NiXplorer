package com.nixplorer.screen.main.tab.files.task

import com.nixplorer.App.Companion.globalClass
import com.nixplorer.App.Companion.logger
import com.nixplorer.R
import com.nixplorer.common.emptyString
import com.nixplorer.common.isNot
import com.nixplorer.common.listFilesAndEmptyDirs
import com.nixplorer.common.orIf
import com.nixplorer.common.toFormattedDate
import com.nixplorer.common.toRelativeString
import com.nixplorer.screen.main.tab.files.holder.ContentHolder
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.holder.ZipFileHolder
import com.nixplorer.screen.main.tab.files.misc.FileMimeType.apkFileType
import com.reandroid.archive.ZipAlign
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import java.io.ByteArrayInputStream
import java.io.File

class CopyTask(
    val sourceFiles: List<ContentHolder>,
    val deleteSourceFiles: Boolean
) : Task() {
    private var parameters: CopyTaskParameters? = null
    private val pendingFiles: ArrayList<TaskContentItem> = arrayListOf()
    private val sourceDirectories = linkedMapOf<File, SafeFileOperations.Identity>()
    private var sourceDirectoryCharacters = 0L
    private val directoryErrors = linkedMapOf<File, String>()
    private val deletedDirectories = hashSetOf<File>()
    private var extractionBytes = 0L
    private var activeJob: kotlinx.coroutines.Job? = null

    override val metadata = createTaskMetadata()
    override val progressMonitor = TaskProgressMonitor(
        status = TaskStatus.PENDING,
        taskTitle = metadata.title,
    )

    override fun recoveryRecord() = OperationRecord(id, metadata.title, if (deleteSourceFiles) "move" else "copy",
        sourceFiles.map { describe(it) }, parameters?.destHolder?.let { describe(it) }, getCurrentStatus().name,
        progressMonitor.summary, pendingFiles.map { item -> OperationItemRecord(describe(item.content),
            parameters?.destHolder?.let { describe(it) + "/" + item.relativePath } ?: item.relativePath,
            item.status.name, item.error, item.copied, item.sourceDeleted, item.sourceIdentity) } +
            sourceDirectories.map { (file, identity) -> OperationItemRecord(file.path, "", if (file in deletedDirectories) "SUCCESS" else "DIRECTORY",
                directoryErrors[file], false, file in deletedDirectories, identity) })

    private fun checkpointItem(item: TaskContentItem) {
        globalClass.taskManager.journal.saveItem(id, OperationItemRecord(describe(item.content),
            parameters?.destHolder?.let { describe(it) + "/" + item.relativePath } ?: item.relativePath,
            item.status.name, item.error, item.copied, item.sourceDeleted, item.sourceIdentity))
    }

    private fun describe(holder: ContentHolder): String = if (holder is ZipFileHolder)
        holder.zipTree.source.uniquePath + "!/" + holder.node.path else holder.uniquePath

    override fun getCurrentStatus() = progressMonitor.status
    override suspend fun validate() = sourceFiles.all { it.isValid() && it.canRead }

    override suspend fun run() {
        if (parameters == null) {
            markAsFailed(globalClass.getString(R.string.unable_to_continue_task))
            return
        }
        run(parameters!!)
    }

    override suspend fun run(params: TaskParameters) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        parameters = params as? CopyTaskParameters

        activeJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        try {
            if (!initializeTask()) return@withContext
            checkpoint()
            executeTaskBasedOnSourceType()
            checkpoint()
            if (deleteSourceFiles) {
                performSourceDeletion()
            }
            alignApkIfNecessary()
            finalizeTask()
            checkpoint()
        } catch (e: java.util.concurrent.CancellationException) {
            progressMonitor.status = TaskStatus.CANCELLED
            progressMonitor.summary = "Cancelled. Completed items retained; incomplete writes discarded."
        } catch (e: Exception) {
            handleTaskError(e)
        }
    }

    override suspend fun continueTask() {
        aborted = false
        pendingFiles.filter { it.status == TaskContentStatus.FAILED }.forEach { it.status = if (it.copied) TaskContentStatus.SUCCESS else TaskContentStatus.PENDING; it.error = null }
        parameters?.let { run(it) } ?: run {
            markAsFailed(globalClass.resources.getString(R.string.task_summary_missing_destination))
        }
    }

    override fun setParameters(params: TaskParameters) {
        parameters = params as? CopyTaskParameters
    }

    // Private helper methods

    private fun alignApkIfNecessary() {
        val destinationHolder = parameters!!.destHolder

        if (destinationHolder !is ZipFileHolder) return

        if (progressMonitor.status == TaskStatus.RUNNING) {
            if (destinationHolder.zipTree.source.extension == apkFileType) {
                progressMonitor.apply {
                    processName = globalClass.resources.getString(R.string.aligning_apk)
                    progress = -1f
                    contentName = emptyString
                }
                com.nixplorer.screen.main.tab.files.zip.ArchiveEdits.update(destinationHolder.zipTree.source.file, { aborted || activeJob?.isActive == false }) { ZipAlign.alignApk(it) }
            }
        }
    }

    private fun createTaskMetadata(): TaskMetadata {
        val time = System.currentTimeMillis().toFormattedDate()
        return TaskMetadata(
            id = id,
            creationTime = time,
            title = globalClass.resources.getString(
                if (deleteSourceFiles) R.string.move else R.string.copy
            ),
            subtitle = globalClass.resources.getString(R.string.task_subtitle, sourceFiles.size),
            displayDetails = sourceFiles.joinToString(", ") { it.displayName },
            fullDetails = buildString {
                sourceFiles.forEach { source ->
                    appendLine(source.displayName)
                }
                appendLine()
                append(time)
            },
            isCancellable = true,
            canMoveToBackground = true
        )
    }

    private fun initializeTask(): Boolean {
        progressMonitor.status = TaskStatus.RUNNING
        SafeFileOperations.checkCancelled { aborted || activeJob?.isActive == false }
        protect = false

        if (sourceFiles.isEmpty()) {
            markAsFailed(globalClass.resources.getString(R.string.task_summary_no_src))
            return false
        }

        parameters?.let { params ->
            if (!validateDestination(params.destHolder)) {
                return false
            }
        } ?: run {
            markAsFailed(globalClass.resources.getString(R.string.task_summary_missing_destination))
            return false
        }

        return true
    }

    private fun validateDestination(destHolder: ContentHolder): Boolean {
        try {
            require(sourceFiles.all { it is LocalFileHolder } || sourceFiles.all { it is ZipFileHolder }) { "Mixed storage sources are not supported" }
            require(sourceFiles.all { it is LocalFileHolder || it is ZipFileHolder }) { "Unsupported storage provider; operation cannot be completed" }
            require(destHolder is LocalFileHolder || destHolder is ZipFileHolder) { "Unsupported storage provider destination" }
            require(destHolder.canWrite && destHolder.isFolder) { "Destination is unavailable or access was revoked" }
            if (sourceFiles.all { it is LocalFileHolder } && destHolder is LocalFileHolder) {
                SafeFileOperations.validateCopyPaths(sourceFiles.map { (it as LocalFileHolder).file }, destHolder.file)
            }
            if (destHolder is ZipFileHolder) {
                require(sourceFiles.none { it is ZipFileHolder && it.zipTree.source.file.canonicalFile == destHolder.zipTree.source.file.canonicalFile }) { "Copy/move within the same archive is not supported safely" }
                require(sourceFiles.none { it is LocalFileHolder && (destHolder.zipTree.source.file.canonicalFile.toPath().startsWith(it.file.canonicalFile.toPath())) }) { "Archive destination overlaps a source" }
            }
            return true
        } catch (e: Exception) { markAsFailed(e.message ?: "Invalid destination"); return false }
    }

    private fun executeTaskBasedOnSourceType() {
        val sample = sourceFiles.first()
        val destHolder = parameters!!.destHolder
        val sourcePath = runBlocking { sample.getParent()?.uniquePath ?: emptyString }
        if (sample is ZipFileHolder) {
            require(sourceFiles.all { it is ZipFileHolder && it.zipTree.source.uniquePath == sample.zipTree.source.uniquePath }) { "Select entries from one archive at a time" }
            com.nixplorer.screen.main.tab.files.zip.ArchiveSafety.validate(sample.zipTree.source.file)
        }

        when {
            sample is LocalFileHolder && destHolder is LocalFileHolder ->
                copyLocalFiles(sourcePath, destHolder)

            sample is LocalFileHolder && destHolder is ZipFileHolder ->
                copyLocalFilesToZip(sourcePath, destHolder)

            sample is ZipFileHolder && destHolder is LocalFileHolder ->
                copyZipFilesToLocal(sourcePath, destHolder)

            sample is ZipFileHolder && destHolder is ZipFileHolder ->
                copyZipFilesToZip(sourcePath, destHolder)

            else ->
                throw IllegalStateException(globalClass.getString(R.string.unsupported_source_destination_combination))
        }
    }

    private fun markAsFailed(info: String) {
        progressMonitor.apply {
            status = if (pendingFiles.any { it.copied }) TaskStatus.PARTIAL_FAILURE else TaskStatus.FAILED
            summary = info
        }
    }

    private fun handleTaskError(e: Exception) {
        logger.logError(e)
        markAsFailed(
            globalClass.resources.getString(
                R.string.task_summary_failed,
                e.message ?: globalClass.getString(R.string.unknown_error)
            )
        )
    }

    private fun finalizeTask() {
        if (progressMonitor.status == TaskStatus.RUNNING) {
            val failures = pendingFiles.count { it.status == TaskContentStatus.FAILED }
            val retainedFolders = if (deleteSourceFiles) sourceDirectories.keys.filter { it !in deletedDirectories && it.exists() } else emptyList()
            progressMonitor.status = copyOutcome(pendingFiles.map { it.status })
            progressMonitor.progress = 1f
            if (failures > 0 && pendingFiles.any { it.copied }) progressMonitor.status = TaskStatus.PARTIAL_FAILURE
            if (retainedFolders.isNotEmpty() && progressMonitor.status == TaskStatus.SUCCESS) progressMonitor.status = TaskStatus.PARTIAL_FAILURE
            progressMonitor.summary = pendingFiles.joinToString("\n") {
                "${it.relativePath}: ${it.status}${it.error?.let { error -> " — $error" } ?: ""}"
            } + if (retainedFolders.isNotEmpty()) "\nSource directories retained for review: ${retainedFolders.joinToString { it.path }}" else ""
            progressMonitor.summary += if (failures > 0) "\nRetry will process failed items only. Check access and free space." else ""
        }
    }

    private fun preparePendingFiles(sourcePath: String) {
        if (pendingFiles.isNotEmpty()) return
        var plannedPathCharacters = 0L
        val destinationLength = parameters!!.destHolder.uniquePath.length
        try {
            sourceFiles.forEach { source ->
                SafeFileOperations.checkCancelled { aborted || activeJob?.isActive == false }
                val items = listFilesWithRelativePath(sourcePath, source)
                require(pendingFiles.size + items.size + sourceDirectories.size <= SafeFileOperations.MAX_ITEMS) { "Too many files; select smaller folders" }
                pendingFiles.addAll(items)
                plannedPathCharacters += items.sumOf { it.content.uniquePath.length.toLong() + it.relativePath.length + destinationLength }
                require(plannedPathCharacters + sourceDirectoryCharacters <= SafeFileOperations.MAX_PLAN_PATH_CHARACTERS) { "Copy plan exceeds memory limit; select smaller folders" }
                progressMonitor.totalContent = pendingFiles.size
            }
            checkpoint()
        } catch (e: Exception) {
            // Enumeration must complete before any writes. Retrying an incomplete plan must enumerate again.
            pendingFiles.clear(); sourceDirectories.clear(); sourceDirectoryCharacters = 0L
            throw e
        }
    }

    private fun updateProgress(index: Int, itemName: String) {
        progressMonitor.apply {
            contentName = itemName
            remainingContent = pendingFiles.size - (index + 1)
            progress = index.toFloat() / pendingFiles.size
        }
    }

    private fun handleConflict(item: TaskContentItem): Boolean {
        return when (commonConflictResolution) {
            TaskContentStatus.SKIP -> {
                item.status = TaskContentStatus.SKIP
                true
            }

            TaskContentStatus.ASK -> {
                item.status = TaskContentStatus.CONFLICT
                progressMonitor.status = TaskStatus.CONFLICT
                globalClass.taskManager.taskInterceptor.interceptTask(item, this)
                false // Pause execution
            }

            TaskContentStatus.REPLACE -> {
                item.status = TaskContentStatus.REPLACE
                true
            }

            else -> true
        }
    }

    private fun performSourceDeletion() {
        if (!deleteSourceFiles) return
        if (progressMonitor.status != TaskStatus.RUNNING) return

        progressMonitor.processName =
            globalClass.resources.getString(R.string.deleting_source_files)
        progressMonitor.progress = -1f // Verification/cleanup has not completed merely because copying finished.

        when (val sample = sourceFiles.first()) {
            is LocalFileHolder -> deleteLocalSources()
            is ZipFileHolder -> deleteZipSources(sample)
        }
    }

    private fun deleteLocalSources() {
        val destination = parameters!!.destHolder
        pendingFiles.filter { it.status == TaskContentStatus.SUCCESS && !it.sourceDeleted }.forEach { item ->
            SafeFileOperations.checkCancelled { aborted || activeJob?.isActive == false }
            // Empty directory cleanup is intentionally separate; never recursively unlink after a move.
            val file = (item.content as LocalFileHolder).file
            if (item.sourceIdentity == null) {
                if (file.isFile) {
                    item.status = TaskContentStatus.FAILED
                    item.error = "Copied successfully; source retained. Verified moves into archives are not supported. Review the archive before deleting the source explicitly."
                }
                return@forEach
            }
            try {
                require(destination is LocalFileHolder) { "Source retained: moving into an archive needs manual source deletion" }
                SafeFileOperations.deleteVerifiedSource(file,
                    SafeFileOperations.contained(destination.file, item.relativePath), item.sourceIdentity!!, { aborted || activeJob?.isActive == false })
                item.sourceDeleted = true
                checkpointItem(item)
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) { item.status = TaskContentStatus.FAILED; item.error = e.message }
        }
        if (destination is LocalFileHolder) sourceDirectories.keys.sortedByDescending { it.path.length }.forEach { directory ->
            if (directory in deletedDirectories || !directory.exists()) return@forEach
            SafeFileOperations.checkCancelled { aborted || activeJob?.isActive == false }
            try {
                val selected = sourceFiles.filterIsInstance<LocalFileHolder>().first { directory.toPath().startsWith(it.file.toPath()) }
                val relative = directory.toRelativeString(selected.file.parentFile!!)
                SafeFileOperations.deleteVerifiedSource(directory, SafeFileOperations.contained(destination.file, relative),
                    sourceDirectories.getValue(directory), { aborted || activeJob?.isActive == false })
                deletedDirectories.add(directory)
                directoryErrors.remove(directory)
                globalClass.taskManager.journal.saveItem(id, OperationItemRecord(directory.path, "", "SUCCESS", null, false, true, sourceDirectories.getValue(directory)))
            } catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) { directoryErrors[directory] = e.message ?: "Source directory retained" }
        }

    }

    private fun deleteZipSources(sample: ZipFileHolder) {
        // Entries can be changed by another app between extraction and deletion. Retain rather than guessing.
        pendingFiles.filter { it.status == TaskContentStatus.SUCCESS }.forEach {
            it.status = TaskContentStatus.FAILED
            it.error = "Copied successfully; archive source retained. Delete it explicitly after reviewing the destination."
        }
    }

    // Specialized copy methods

    private fun copyLocalFiles(sourcePath: String, destinationHolder: LocalFileHolder) {
        progressMonitor.processName = globalClass.resources.getString(R.string.counting_files)
        preparePendingFiles(sourcePath)

        if (progressMonitor.status == TaskStatus.FAILED) return

        progressMonitor.apply {
            totalContent = pendingFiles.size
            processName = if (deleteSourceFiles)
                globalClass.resources.getString(R.string.moving)
            else globalClass.resources.getString(R.string.copying)
        }

        pendingFiles.forEachIndexed { index, item ->
            if (aborted) {
                progressMonitor.status = TaskStatus.CANCELLED
                return
            }

            if (item.status isNot TaskContentStatus.PENDING
                && item.status isNot TaskContentStatus.REPLACE
                && item.status isNot TaskContentStatus.CONFLICT
            ) {
                return@forEachIndexed
            }

            val sourceFile = (item.content as LocalFileHolder).file
            val destinationFile = SafeFileOperations.contained(destinationHolder.file, item.relativePath)

            updateProgress(index, sourceFile.name)

            if (item.status == TaskContentStatus.CONFLICT && !handleConflict(item)) {
                return
            }

            if (item.status == TaskContentStatus.PENDING) {
                val conflictExists = destinationFile.exists()
                if (conflictExists && !handleConflict(item)) {
                    return
                }
            }

            when (item.status) {
                TaskContentStatus.PENDING, TaskContentStatus.REPLACE -> {
                    item.status = if (copyLocalFile(
                            item,
                            sourceFile,
                            destinationFile,
                            item.status == TaskContentStatus.REPLACE
                        )
                    ) {
                        TaskContentStatus.SUCCESS
                    } else {
                        TaskContentStatus.FAILED
                    }
                }

                else -> { /* Already handled */
                }
            }
            item.copied = item.copied || item.status == TaskContentStatus.SUCCESS
            checkpointItem(item)
        }
    }

    private fun copyLocalFile(item: TaskContentItem, source: File, destination: File, overwrite: Boolean): Boolean {
        return try {
            SafeFileOperations.checkCancelled { aborted || activeJob?.isActive == false }
            require(item.content.isFolder == source.isDirectory) { "Source type changed; reselect the source before retrying: $source" }
            if (source.isFile) {
                val expected = SafeFileOperations.identity(source, { aborted || activeJob?.isActive == false })
                val root = (parameters!!.destHolder as LocalFileHolder).file
                val baseProgress = progressMonitor.progress
                SafeFileOperations.stagedStream(root, item.relativePath, overwrite, Long.MAX_VALUE,
                    { aborted || activeJob?.isActive == false }, expected.bytes, expected.digest,
                    beforePublish = { require(SafeFileOperations.identity(source, { aborted || activeJob?.isActive == false }) == expected) { "Source changed while copying; original retained" } },
                    operationId = id, onProgress = { bytes -> progressMonitor.progress = baseProgress +
                        (bytes.toFloat() / maxOf(1L, expected.bytes)).coerceAtMost(1f) * 0.9f / pendingFiles.size },
                    input = { source.inputStream() })
                item.sourceIdentity = expected
            } else {
                require(source.isDirectory) { "Source unavailable: $source" }
                SafeFileOperations.directory(destination)
            }
            item.copied = true
            item.error = null
            true
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) { logger.logError(e); item.error = e.message ?: "Copy failed"; false }
    }

    private fun copyLocalFilesToZip(sourcePath: String, destinationHolder: ZipFileHolder) {
        progressMonitor.processName = globalClass.resources.getString(R.string.counting_files)
        preparePendingFiles(sourcePath)

        if (progressMonitor.status == TaskStatus.FAILED) return

        progressMonitor.apply {
            totalContent = pendingFiles.size
            processName = if (deleteSourceFiles)
                globalClass.resources.getString(R.string.moving)
            else globalClass.resources.getString(R.string.copying)
        }

        try {
            ZipFile(destinationHolder.zipTree.source.file).use { targetZipFile ->
                pendingFiles.forEachIndexed { index, item ->
                    if (aborted) {
                        progressMonitor.status = TaskStatus.CANCELLED
                        return
                    }

                    if (item.status isNot TaskContentStatus.PENDING
                        && item.status isNot TaskContentStatus.REPLACE
                        && item.status isNot TaskContentStatus.CONFLICT
                    ) {
                        return@forEachIndexed
                    }

                    updateProgress(index, item.content.displayName)

                    if (item.status == TaskContentStatus.CONFLICT && !handleConflict(item)) {
                        return
                    }

                    val targetPath =
                        createZipEntryPath(destinationHolder.node.path, item.relativePath)

                    if (item.status == TaskContentStatus.PENDING) {
                        val existingHeader = ZipFile(destinationHolder.zipTree.source.file).use { it.getFileHeader(targetPath) }
                        val conflictExists = existingHeader != null && !existingHeader.isDirectory
                        if (conflictExists && !handleConflict(item)) {
                            return
                        }
                    }

                    when (item.status) {
                        TaskContentStatus.PENDING, TaskContentStatus.REPLACE -> {
                            item.status = if (addLocalFileToZip(
                                    targetZipFile,
                                    item,
                                    targetPath,
                                    item.status == TaskContentStatus.REPLACE
                                )
                            ) {
                                TaskContentStatus.SUCCESS
                            } else {
                                TaskContentStatus.FAILED
                            }
                        }

                        else -> { /* Already handled */
                        }
                    }
                    item.copied = item.copied || item.status == TaskContentStatus.SUCCESS
                    checkpointItem(item)
                }
            }
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) {
            throw RuntimeException(globalClass.getString(R.string.failed_to_copy_files_to_zip), e)
        }
    }

    private fun addLocalFileToZip(
        zipFile: ZipFile,
        item: TaskContentItem,
        targetPath: String,
        overwrite: Boolean
    ): Boolean {
        return try {
            val sourceFile = (item.content as LocalFileHolder).file
            val params = ZipParameters().apply {
                isOverrideExistingFilesInZip = overwrite
                fileNameInZip = targetPath
            }

            if (sourceFile.isFile) {
                com.nixplorer.screen.main.tab.files.zip.ArchiveEdits.update(zipFile.file, { aborted || activeJob?.isActive == false }, id) { stage ->
                    ZipFile(stage).use { stagedZip ->
                        require(overwrite || stagedZip.getFileHeader(targetPath) == null) { "Archive destination appeared during copying; retry to resolve conflict" }
                        stagedZip.addFile(sourceFile, params)
                    }
                }
            } else {
                com.nixplorer.screen.main.tab.files.zip.ArchiveEdits.update(zipFile.file, { aborted || activeJob?.isActive == false }, id) { stage ->
                    ZipFile(stage).use { it.addStream(ByteArrayInputStream(ByteArray(0)), params.apply { fileNameInZip = targetPath.trimEnd('/') + "/" }) }
                }
            }
            true
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) {
            logger.logError(e)
            item.error = e.message ?: "Operation failed"
            false
        }
    }

    private fun copyZipFilesToLocal(sourcePath: String, destinationHolder: LocalFileHolder) {
        progressMonitor.processName = globalClass.resources.getString(R.string.counting_files)
        preparePendingFiles(sourcePath)

        if (progressMonitor.status == TaskStatus.FAILED) return

        progressMonitor.apply {
            totalContent = pendingFiles.size
            processName = globalClass.resources.getString(R.string.extracting)
        }

        val sourceZipFile = (sourceFiles.first() as ZipFileHolder).zipTree.source.file
        com.nixplorer.screen.main.tab.files.zip.ArchiveSafety.validate(sourceZipFile)
        try {
            ZipFile(sourceZipFile).use { sourceZip ->
                pendingFiles.forEachIndexed { index, item ->
                    if (aborted) {
                        progressMonitor.status = TaskStatus.CANCELLED
                        return
                    }

                    if (item.status isNot TaskContentStatus.PENDING
                        && item.status isNot TaskContentStatus.REPLACE
                        && item.status isNot TaskContentStatus.CONFLICT
                    ) {
                        return@forEachIndexed
                    }

                    val sourceHolder = item.content as ZipFileHolder
                    val destinationFile = SafeFileOperations.contained(destinationHolder.file, item.relativePath)

                    updateProgress(index, sourceHolder.displayName)

                    if (item.status == TaskContentStatus.CONFLICT && !handleConflict(item)) {
                        return
                    }

                    if (item.status == TaskContentStatus.PENDING) {
                        val conflictExists = destinationFile.exists()
                        if (conflictExists && !handleConflict(item)) {
                            return
                        }
                    }

                    when (item.status) {
                        TaskContentStatus.PENDING, TaskContentStatus.REPLACE -> {
                            item.status =
                                if (extractZipEntry(sourceZip, sourceHolder, destinationFile, item)) {
                                    TaskContentStatus.SUCCESS
                                } else {
                                    TaskContentStatus.FAILED
                                }
                        }

                        else -> { /* Already handled */
                        }
                    }
                    item.copied = item.copied || item.status == TaskContentStatus.SUCCESS
                    checkpointItem(item)
                }
            }
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) {
            throw RuntimeException(
                globalClass.getString(R.string.failed_to_extract_files_from_zip),
                e
            )
        }
    }

    private fun extractZipEntry(sourceZip: ZipFile, sourceHolder: ZipFileHolder, destinationFile: File, item: TaskContentItem): Boolean {
        return try {
            val root = (parameters!!.destHolder as LocalFileHolder).file
            if (sourceHolder.isFile()) {
                val header = requireNotNull(sourceZip.getFileHeader(sourceHolder.node.path)) { "Archive entry missing" }
                val remaining = com.nixplorer.screen.main.tab.files.zip.ArchiveSafety.MAX_TOTAL_BYTES - extractionBytes
                val baseProgress = progressMonitor.progress
                val bytes = SafeFileOperations.stagedStream(root, item.relativePath, item.status == TaskContentStatus.REPLACE,
                    minOf(remaining, com.nixplorer.screen.main.tab.files.zip.ArchiveSafety.MAX_ENTRY_BYTES),
                    { aborted || activeJob?.isActive == false }, expectedBytes = header.uncompressedSize,
                    operationId = id, onProgress = { bytes -> progressMonitor.progress = baseProgress +
                        (bytes.toFloat() / maxOf(1L, header.uncompressedSize)).coerceAtMost(1f) * 0.9f / pendingFiles.size },
                    input = { sourceZip.getInputStream(header) })
                extractionBytes += bytes
            } else SafeFileOperations.directory(SafeFileOperations.contained(root, item.relativePath))
            true
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) { logger.logError(e); item.error = e.message; false }
    }

    private fun copyZipFilesToZip(sourcePath: String, destinationHolder: ZipFileHolder) {
        progressMonitor.processName = globalClass.resources.getString(R.string.counting_files)
        preparePendingFiles(sourcePath)

        if (progressMonitor.status == TaskStatus.FAILED) return

        progressMonitor.apply {
            totalContent = pendingFiles.size
            processName = if (deleteSourceFiles)
                globalClass.resources.getString(R.string.moving)
            else globalClass.resources.getString(R.string.copying)
        }

        val sourceFile = (sourceFiles.first() as ZipFileHolder).zipTree.source.file
        val destFile = destinationHolder.zipTree.source.file

        try {
            ZipFile(sourceFile).use { sourceZip ->
                ZipFile(destFile).use { destZip ->
                    pendingFiles.forEachIndexed { index, item ->
                        if (aborted) {
                            progressMonitor.status = TaskStatus.CANCELLED
                            return
                        }

                        if (item.status isNot TaskContentStatus.PENDING
                            && item.status isNot TaskContentStatus.REPLACE
                            && item.status isNot TaskContentStatus.CONFLICT
                        ) {
                            return@forEachIndexed
                        }

                        updateProgress(index, item.content.displayName)

                        if (item.status == TaskContentStatus.CONFLICT && !handleConflict(item)
                        ) {
                            return
                        }

                        val targetPath =
                            createZipEntryPath(destinationHolder.node.path, item.relativePath)

                        if (item.status == TaskContentStatus.PENDING) {
                            val existingHeader = ZipFile(destinationHolder.zipTree.source.file).use { it.getFileHeader(targetPath) }
                            val conflictExists =
                                existingHeader != null && !existingHeader.isDirectory
                            if (conflictExists && !handleConflict(item)) {
                                return
                            }
                        }

                        when (item.status) {
                            TaskContentStatus.PENDING, TaskContentStatus.REPLACE -> {
                                item.status =
                                    if (copyZipEntry(sourceZip, destZip, item, targetPath)) {
                                        TaskContentStatus.SUCCESS
                                    } else {
                                        TaskContentStatus.FAILED
                                    }
                            }

                            else -> { /* Already handled */
                            }
                        }
                        item.copied = item.copied || item.status == TaskContentStatus.SUCCESS
                        checkpointItem(item)
                    }
                }
            }
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) {
            throw RuntimeException(globalClass.getString(R.string.failed_to_copy_zip_entries), e)
        }
    }

    private fun copyZipEntry(
        sourceZip: ZipFile,
        destZip: ZipFile,
        item: TaskContentItem,
        targetPath: String
    ): Boolean {
        return try {
            val sourceHolder = item.content as ZipFileHolder
            val params = ZipParameters().apply {
                fileNameInZip = if (sourceHolder.isFile()) targetPath else "$targetPath/"
                isOverrideExistingFilesInZip = item.status == TaskContentStatus.REPLACE
            }

            if (sourceHolder.isFile()) {
                sourceZip.getInputStream(sourceZip.getFileHeader(sourceHolder.node.path))
                    .use { input ->
                        com.nixplorer.screen.main.tab.files.zip.ArchiveEdits.update(destZip.file, { aborted || activeJob?.isActive == false }, id) { stage ->
                            ZipFile(stage).use { stagedZip ->
                                require(item.status == TaskContentStatus.REPLACE || stagedZip.getFileHeader(targetPath) == null) { "Archive destination appeared during copying; retry to resolve conflict" }
                                stagedZip.addStream(input, params)
                            }
                        }
                    }
            } else {
                com.nixplorer.screen.main.tab.files.zip.ArchiveEdits.update(destZip.file, { aborted || activeJob?.isActive == false }, id) { stage ->
                    ZipFile(stage).use { it.addStream(ByteArrayInputStream(ByteArray(0)), params) }
                }
            }
            true
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (e: Exception) {
            logger.logError(e)
            item.error = e.message ?: "Operation failed"
            false
        }
    }

    private fun createZipEntryPath(basePath: String, relativePath: String): String {
        return SafeFileOperations.relativePath(if (basePath.isEmpty()) {
            relativePath
        } else {
            "$basePath/$relativePath"
        })
    }

    private fun listFilesWithRelativePath(
        basePath: String,
        startFile: ContentHolder
    ): List<TaskContentItem> {
        if (startFile.isFile()) {
            return listOf(
                TaskContentItem(
                    content = startFile,
                    relativePath = startFile.displayName,
                    status = TaskContentStatus.PENDING
                )
            )
        }

        return when (startFile) {
            is LocalFileHolder -> {
                val items = mutableListOf<TaskContentItem>()
                SafeFileOperations.walk(startFile.file, { aborted || activeJob?.isActive == false },
                    onDirectory = { directory ->
                        if (directory !in sourceDirectories) {
                            sourceDirectories[directory] = SafeFileOperations.directoryIdentity(directory)
                            sourceDirectoryCharacters += directory.path.length
                        }
                    }) { file ->
                    items.add(TaskContentItem(content = LocalFileHolder(file),
                        relativePath = file.toRelativeString(startFile.file.parentFile ?: startFile.file).orIf(startFile.displayName) { it.isEmpty() },
                        status = TaskContentStatus.PENDING))
                    progressMonitor.contentName = file.name
                    progressMonitor.totalContent = items.size
                }
                items
            }

            is ZipFileHolder -> {
                startFile.node.listFilesAndEmptyDirs().map { node ->
                    TaskContentItem(
                        content = ZipFileHolder(startFile.zipTree, node),
                        relativePath = node.path.toRelativeString(basePath)
                            .orIf(startFile.displayName) { it.isEmpty() },
                        status = TaskContentStatus.PENDING
                    )
                }
            }

            else -> throw IllegalArgumentException("Unsupported provider operation")
        }
    }
}
