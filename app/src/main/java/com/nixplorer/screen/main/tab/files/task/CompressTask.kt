package com.nixplorer.screen.main.tab.files.task

import com.nixplorer.App.Companion.globalClass
import com.nixplorer.App.Companion.logger
import com.nixplorer.R
import com.nixplorer.common.emptyString
import com.nixplorer.common.toFormattedDate
import com.nixplorer.screen.main.tab.files.holder.ContentHolder
import net.lingala.zip4j.ZipFile
import java.io.File

class CompressTask(
    val sourceContent: List<ContentHolder>
) : Task() {
    private var parameters: CompressTaskParameters? = null
    private var pendingContent = arrayListOf<TaskContentItem>()

    override val metadata = System.currentTimeMillis().toFormattedDate().let { time ->
        TaskMetadata(
            id = id,
            creationTime = time,
            title = globalClass.resources.getString(R.string.compress),
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

    override fun recoveryRecord() = OperationRecord(id, metadata.title, "compress", sourceContent.map { it.uniquePath },
        parameters?.destPath, getCurrentStatus().name, progressMonitor.summary)

    override fun getCurrentStatus() = progressMonitor.status

    override suspend fun validate() = sourceContent.find { !it.isValid() } == null

    private fun markAsFailed(info: String) {
        progressMonitor.apply {
            status = TaskStatus.FAILED
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
        parameters = params as CompressTaskParameters
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

        val destination = File(requireNotNull(parameters).destPath)
        if (sourceContent.map { it.displayName }.distinct().size != sourceContent.size || destination.exists() || sourceContent.any { it !is com.nixplorer.screen.main.tab.files.holder.LocalFileHolder ||
            (it.isFolder && destination.canonicalFile.toPath().startsWith(File(it.uniquePath).canonicalFile.toPath())) || File(it.uniquePath).canonicalFile == destination.canonicalFile }) {
            markAsFailed("Choose a new archive outside the source folders. Existing archives and unsupported providers cannot be compressed in place.")
            return
        }
        val basePath = sourceContent[0].getParent()?.uniquePath ?: emptyString

        if (pendingContent.isEmpty()) {
            sourceContent.forEachIndexed { index, content ->
                pendingContent.add(
                    TaskContentItem(
                        content = content,
                        relativePath = content.uniquePath.removePrefix("/$basePath"),
                        status = TaskContentStatus.PENDING
                    )
                )
            }
        }

        progressMonitor.apply {
            totalContent = pendingContent.size
            processName = globalClass.getString(R.string.compressing)
            progress = 0.1f
        }

        var stage: File? = null
        try {
            // Check abortion before starting compression
            if (aborted) {
                markAsAborted()
                return
            }

            SafeFileOperations.directory(destination.parentFile!!)
            stage = File.createTempFile(".nix-compress-$id-", ".zip", destination.parentFile)
            java.util.zip.ZipOutputStream(stage.outputStream()).use { }
            pendingContent.forEach { it.status = TaskContentStatus.PENDING }
            ZipFile(stage).use { zipOut ->
                pendingContent.forEachIndexed { index, itemToCompress ->
                    if (aborted) {
                        markAsAborted()
                        return
                    }

                    if (itemToCompress.status == TaskContentStatus.PENDING) {
                        val progressPercent =
                            0.1f + (0.9f * (index.toFloat() / pendingContent.size))

                        progressMonitor.apply {
                            contentName = itemToCompress.content.displayName
                            remainingContent = pendingContent.size - (index + 1)
                            progress = progressPercent
                        }

                        try {
                            if (itemToCompress.content.isFolder) {
                                addFolderToZip(zipOut, itemToCompress.content)
                            } else {
                                addFileToZip(zipOut, itemToCompress.content)
                            }
                            // Commit item success only after the validated archive is published.
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
            com.nixplorer.screen.main.tab.files.zip.ArchiveSafety.validate(stage, readData = true, cancelled = { aborted })
            SafeFileOperations.checkCancelled { aborted }
            SafeFileOperations.replace(stage, destination, overwrite = false)
            pendingContent.forEach { it.status = TaskContentStatus.SUCCESS }
        } catch (e: java.util.concurrent.CancellationException) {
            progressMonitor.status = TaskStatus.CANCELLED
            progressMonitor.summary = "Cancelled; original archive retained"
        } catch (e: Exception) {
            logger.logError(e)
            markAsFailed(
                globalClass.resources.getString(
                    R.string.task_summary_failed,
                    e.message ?: emptyString
                )
            )
            return
        } finally { stage?.delete() }

        if (progressMonitor.status == TaskStatus.RUNNING) {
            progressMonitor.apply {
                status = TaskStatus.SUCCESS
                progress = 1.0f
                processName = globalClass.getString(R.string.completed)
                summary = globalClass.getString(R.string.task_completed)
            }
        }
    }

    private fun addFileToZip(zipOut: ZipFile, fileToCompress: ContentHolder) {
        zipOut.addFile(File(fileToCompress.uniquePath))
    }

    private fun addFolderToZip(zipOut: ZipFile, folderToCompress: ContentHolder) {
        zipOut.addFolder(File(folderToCompress.uniquePath))
    }

    override fun setParameters(params: TaskParameters) {
        parameters = params as CompressTaskParameters
    }

    override suspend fun continueTask() {
        if (parameters == null) {
            markAsFailed(globalClass.getString(R.string.unable_to_continue_task))
            return
        }
        run(parameters!!)
    }
}