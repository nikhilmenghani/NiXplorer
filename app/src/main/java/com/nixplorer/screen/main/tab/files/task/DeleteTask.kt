package com.nixplorer.screen.main.tab.files.task

import com.nixplorer.App.Companion.globalClass
import com.nixplorer.App.Companion.logger
import com.nixplorer.R
import com.nixplorer.common.emptyString
import com.nixplorer.common.toFormattedDate
import com.nixplorer.screen.main.tab.files.holder.ContentHolder
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.holder.ZipFileHolder
import com.nixplorer.screen.main.tab.files.misc.FileMimeType.apkFileType
import com.reandroid.archive.ZipAlign

class DeleteTask(
    val sourceContent: List<ContentHolder>
) : Task() {
    private var parameters: DeleteTaskParameters? = null
    private var pendingContent = arrayListOf<DeleteContentItem>()
    private val preparedEntries = linkedMapOf<String, OperationItemRecord>()

    override val metadata = System.currentTimeMillis().toFormattedDate().let { time ->
        TaskMetadata(
            id = id,
            creationTime = time,
            title = globalClass.resources.getString(R.string.delete),
            subtitle = globalClass.resources.getString(R.string.task_subtitle, sourceContent.size),
            displayDetails = sourceContent.joinToString(", ") { it.displayName },
            fullDetails = buildString {
                sourceContent.forEachIndexed { index, source ->
                    append(source.displayName)
                    append("\n")
                }
                append("\n")
                append(time)
            },
            isCancellable = true,
            canMoveToBackground = true
        )
    }

    override val progressMonitor = TaskProgressMonitor(
        status = TaskStatus.PENDING,
        taskTitle = metadata.title,
    )

    override fun recoveryRecord() = OperationRecord(id, metadata.title, "delete",
        sourceContent.map { if (it is ZipFileHolder) it.zipTree.source.uniquePath + "!/" + it.node.path else it.uniquePath },
        null, getCurrentStatus().name, progressMonitor.summary,
        pendingContent.map { OperationItemRecord(it.source.uniquePath, "", it.status.name, null, false,
            it.status == TaskContentStatus.SUCCESS, null) } + preparedEntries.values)

    override fun getCurrentStatus() = progressMonitor.status

    override suspend fun validate() = sourceContent.find { !it.isValid() } == null

    private fun markAsFailed(info: String) {
        progressMonitor.apply {
            status = if (preparedEntries.values.any { it.sourceDeleted }) TaskStatus.PARTIAL_FAILURE else TaskStatus.FAILED
            summary = info
            progress = 0f
        }
    }

    private fun markAsAborted() {
        progressMonitor.apply {
            status = TaskStatus.CANCELLED
            summary = globalClass.getString(R.string.task_aborted)
        }
    }

    override suspend fun run() {
        if (parameters == null) {
            markAsFailed(globalClass.getString(R.string.unable_to_continue_task))
            return
        }
        run(parameters!!)
    }

    override suspend fun run(params: TaskParameters) {
        parameters = params as DeleteTaskParameters
        progressMonitor.status = TaskStatus.RUNNING
        protect = false

        // Check abortion early
        if (aborted) {
            markAsAborted()
            return
        }

        if (sourceContent.isEmpty()) {
            markAsFailed(globalClass.resources.getString(R.string.task_summary_no_src))
            return
        }

        progressMonitor.apply {
            processName = globalClass.resources.getString(R.string.preparing)
            progress = 0.05f
        }

        if (pendingContent.isEmpty()) {
            sourceContent.forEach { content ->
                pendingContent.add(
                    DeleteContentItem(source = content, status = TaskContentStatus.PENDING)
                )
            }
        }

        progressMonitor.apply {
            totalContent = pendingContent.size
            processName = globalClass.getString(R.string.deleting)
            progress = 0.1f
        }

        // Check source type only once using the first item
        val sampleContent = sourceContent.first()
        when (sampleContent) {
            is LocalFileHolder -> handleLocalFileDeletion()
            is ZipFileHolder -> handleZipFileDeletion()
            else -> {
                markAsFailed(globalClass.getString(R.string.unsupported_source_type))
                return
            }
        }

        // Handle APK alignment if needed
        if (progressMonitor.status == TaskStatus.RUNNING && sampleContent is ZipFileHolder) {
            if (sampleContent.zipTree.source.extension == apkFileType) {
                // Check abortion before alignment
                if (aborted) {
                    markAsAborted()
                    return
                }

                progressMonitor.apply {
                    processName = globalClass.resources.getString(R.string.aligning_apk)
                    progress = 0.95f
                    contentName = emptyString
                }

                try {
                    com.nixplorer.screen.main.tab.files.zip.ArchiveEdits.update(sampleContent.zipTree.source.file, { aborted }, id) { ZipAlign.alignApk(it) }
                } catch (e: Exception) {
                    // Don't fail the entire task for alignment issues
                    logger.logError(e)
                }
            }
        }

        if (progressMonitor.status == TaskStatus.RUNNING) {
            progressMonitor.apply {
                status = TaskStatus.SUCCESS
                progress = 1.0f
                processName = globalClass.getString(R.string.completed)
                summary = globalClass.getString(
                    R.string.task_completed
                )
            }
        }
    }

    private suspend fun handleLocalFileDeletion() {
        pendingContent.forEachIndexed { index, itemToDelete ->
            if (aborted) {
                markAsAborted()
                return
            }

            if (itemToDelete.status == TaskContentStatus.PENDING) {
                val progressPercent = 0.1f + (0.8f * (index.toFloat() / pendingContent.size))

                progressMonitor.apply {
                    contentName = itemToDelete.source.displayName
                    remainingContent = pendingContent.size - (index + 1)
                    progress = progressPercent
                }

                try {
                    val localFile = itemToDelete.source as LocalFileHolder
                    SafeFileOperations.deleteTree(localFile.file, id, { aborted }, onPrepared = { path, retained ->
                        val record = OperationItemRecord(path, retained, "DELETE_PREPARED", null, false, false, null)
                        preparedEntries[path] = record
                        globalClass.taskManager.journal.saveItem(id, record)
                    }) { path ->
                        progressMonitor.contentName = java.io.File(path).name
                        val record = preparedEntries.getValue(path).copy(status = "SUCCESS", sourceDeleted = true)
                        preparedEntries[path] = record
                        globalClass.taskManager.journal.saveItem(id, record)
                    }
                    itemToDelete.status = TaskContentStatus.SUCCESS
                    checkpoint()
                } catch (e: java.util.concurrent.CancellationException) {
                    progressMonitor.status = TaskStatus.CANCELLED
                    progressMonitor.summary = "Deletion cancelled. Some entries may already be deleted; remaining content was retained. Review before retrying."
                    return
                } catch (e: Exception) {
                    logger.logError(e)
                    markAsFailed(
                        globalClass.resources.getString(
                            R.string.task_summary_failed,
                            e.message ?: emptyString
                        )
                    )
                    return
                }
            }
        }
    }

    private suspend fun handleZipFileDeletion() {
        val holder = sourceContent.first() as ZipFileHolder
        try {
            val selected = pendingContent.filter { it.status == TaskContentStatus.PENDING }
            require(selected.all { (it.source as ZipFileHolder).zipTree.source.uniquePath == holder.zipTree.source.uniquePath }) { "Select entries from one archive at a time" }
            val paths = selected.map { (it.source as ZipFileHolder).node.path }
            com.nixplorer.screen.main.tab.files.zip.ArchiveEdits.update(holder.zipTree.source.file, { aborted }, id) { stage ->
                net.lingala.zip4j.ZipFile(stage).use { zip ->
                    val removals = zip.fileHeaders.map { it.fileName }.filter { path ->
                        paths.any { selectedPath -> path.trimEnd('/') == selectedPath || path.startsWith("$selectedPath/") }
                    }
                    if (removals.isNotEmpty()) zip.removeFiles(removals)
                }
            }
            selected.forEach { it.status = TaskContentStatus.SUCCESS }
            holder.zipTree.reset()
        } catch (e: java.util.concurrent.CancellationException) {
            progressMonitor.status = TaskStatus.CANCELLED
            progressMonitor.summary = "Cancelled; original archive retained"
        } catch (e: Exception) {
            logger.logError(e)
            markAsFailed("Archive was not deleted: ${e.message}")
        }
    }

    override suspend fun continueTask() {
        aborted = false
        if (parameters == null) {
            markAsFailed(globalClass.getString(R.string.unable_to_continue_task))
            return
        }
        run(parameters!!)
    }

    override fun setParameters(params: TaskParameters) {
        parameters = params as DeleteTaskParameters
    }

    internal data class DeleteContentItem(
        val source: ContentHolder,
        var status: TaskContentStatus
    )
}
