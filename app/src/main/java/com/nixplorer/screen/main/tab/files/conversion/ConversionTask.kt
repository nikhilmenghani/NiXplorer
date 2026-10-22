package com.nixplorer.screen.main.tab.files.conversion

import android.media.MediaScannerConnection
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.App.Companion.logger
import com.nixplorer.R
import com.nixplorer.common.toFormattedDate
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.task.OperationItemRecord
import com.nixplorer.screen.main.tab.files.task.OperationRecord
import com.nixplorer.screen.main.tab.files.task.SafeFileOperations
import com.nixplorer.screen.main.tab.files.task.Task
import com.nixplorer.screen.main.tab.files.task.TaskContentStatus
import com.nixplorer.screen.main.tab.files.task.TaskMetadata
import com.nixplorer.screen.main.tab.files.task.TaskParameters
import com.nixplorer.screen.main.tab.files.task.TaskProgressMonitor
import com.nixplorer.screen.main.tab.files.task.TaskStatus
import com.nixplorer.screen.main.tab.files.task.copyOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException

/** Sequential batch conversion runs through the existing foreground task service. Originals are never modified. */
class ConversionTask(
    val sourceContent: List<LocalFileHolder>,
    val format: ConversionFormat,
    val quality: Int = 90
) : Task() {
    private data class Entry(
        val source: File,
        var destination: File? = null,
        var status: TaskContentStatus = TaskContentStatus.PENDING,
        var error: String? = null,
        var identity: SafeFileOperations.Identity? = null
    ) {
        fun record() = OperationItemRecord(source.path, destination?.path.orEmpty(), status.name,
            error, status == TaskContentStatus.SUCCESS, false, identity)
    }

    private var parameters: ConversionTaskParameters? = null
    private val entries = sourceContent.map { Entry(it.file) }
    override val metadata = System.currentTimeMillis().toFormattedDate().let { time ->
        TaskMetadata(id, time, "Convert to ${format.label}",
            globalClass.resources.getQuantityString(R.plurals.folder_file_count, sourceContent.size, sourceContent.size),
            sourceContent.joinToString(", ") { it.displayName },
            sourceContent.joinToString("\n") { it.uniquePath } + "\n\n" + time,
            isCancellable = true, canMoveToBackground = true)
    }
    override val progressMonitor = TaskProgressMonitor(taskTitle = metadata.title)

    override fun getCurrentStatus() = progressMonitor.status

    override fun recoveryRecord() = OperationRecord(id, metadata.title, "convert:${format.extension}",
        sourceContent.map { it.uniquePath }, parameters?.destinationPath, getCurrentStatus().name,
        progressMonitor.summary, entries.map { it.record() })

    // Per-file errors are reported together; one missing or unreadable source must not invalidate its peers.
    override suspend fun validate() = entries.isNotEmpty() && entries.size <= SafeFileOperations.MAX_ITEMS

    override fun setParameters(params: TaskParameters) {
        parameters = params as ConversionTaskParameters
    }

    override suspend fun run() {
        val params = parameters
        if (params == null) {
            progressMonitor.status = TaskStatus.FAILED
            progressMonitor.summary = "Choose a destination folder before converting"
            protect = false
            return
        }
        run(params)
    }

    override suspend fun continueTask() = run()

    override suspend fun run(params: TaskParameters) = withContext(Dispatchers.IO) {
        setParameters(params)
        protect = false
        progressMonitor.status = TaskStatus.RUNNING
        progressMonitor.processName = "Preparing conversion"
        progressMonitor.totalContent = entries.size
        progressMonitor.remainingContent = entries.count { it.status != TaskContentStatus.SUCCESS }
        val job = currentCoroutineContext()[Job]
        val cancelled = { aborted || job?.isActive == false }
        var wasCancelled = false
        var preparationError: String? = null
        try {
            require(entries.isNotEmpty()) { "No files selected" }
            require(entries.size <= SafeFileOperations.MAX_ITEMS) { "Too many files in one batch" }
            require(entries.sumOf { it.source.path.length.toLong() } <= SafeFileOperations.MAX_PLAN_PATH_CHARACTERS) {
                "Selected paths exceed the batch memory limit; choose fewer files"
            }
            SafeFileOperations.checkCancelled(cancelled)
            val chosenDirectory = File(requireNotNull(parameters).destinationPath)
            SafeFileOperations.directory(chosenDirectory)
            val directory = chosenDirectory.canonicalFile
            val planner = ConversionOutputs.Planner(directory, format,
                entries.filter { it.status == TaskContentStatus.SUCCESS }.mapNotNull { it.destination })
            entries.filter { it.status != TaskContentStatus.SUCCESS }.forEach { entry ->
                // A new retry never touches a previous output or a file created by another operation.
                entry.destination = planner.next(entry.source)
                entry.status = TaskContentStatus.PENDING
                entry.error = null
            }
            checkpoint() // Durable source -> destination plan before the first decoder starts.
            var processed = entries.count { it.status == TaskContentStatus.SUCCESS }
            for (entry in entries.filter { it.status != TaskContentStatus.SUCCESS }) {
                SafeFileOperations.checkCancelled(cancelled)
                progressMonitor.contentName = entry.source.name
                progressMonitor.processName = "Converting to ${format.label}"
                progressMonitor.progress = processed.toFloat() / entries.size
                var stage: File? = null
                try {
                    require(format in ConversionCatalog.formatsFor(entry.source)) { "This file cannot be converted to ${format.label}" }
                    require(!Files.isSymbolicLink(entry.source.toPath()) && entry.source.isFile && entry.source.canRead()) {
                        "Source is unavailable or unreadable"
                    }
                    entry.identity = SafeFileOperations.identity(entry.source, cancelled)
                    globalClass.taskManager.journal.saveItem(id, entry.record())
                    stage = File.createTempFile(".nix-stage-$id-", ".${format.extension}", directory)
                    when (format) {
                        ConversionFormat.JPEG, ConversionFormat.PNG, ConversionFormat.WEBP ->
                            ImageConverter.convert(entry.source, stage, format, quality, cancelled)
                        ConversionFormat.PDF, ConversionFormat.TXT, ConversionFormat.HTML, ConversionFormat.DOCX ->
                            DocumentConverter.convert(globalClass, entry.source, stage, format, cancelled)
                        ConversionFormat.MP4, ConversionFormat.M4A, ConversionFormat.WAV ->
                            MediaConverter.convert(globalClass, entry.source, stage, format, cancelled) { fraction ->
                                progressMonitor.progress = (processed + fraction.coerceIn(0f, 0.98f)) / entries.size
                            }
                    }
                    SafeFileOperations.checkCancelled(cancelled)
                    require(stage.length() > 0 || format == ConversionFormat.TXT) { "Converter produced an empty file" }
                    require(SafeFileOperations.identity(entry.source, cancelled) == entry.identity) {
                        "Source changed during conversion; output was not published"
                    }
                    while (true) {
                        SafeFileOperations.checkCancelled(cancelled)
                        val target = requireNotNull(entry.destination)
                        try {
                            SafeFileOperations.replace(stage, target, overwrite = false)
                            break
                        } catch (error: IOException) {
                            if (!stage.exists() || !Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw error
                            // Another writer won this name after planning. Keep the encoded stage and choose a new name.
                            entry.destination = planner.next(entry.source)
                            globalClass.taskManager.journal.saveItem(id, entry.record())
                        }
                    }
                    entry.status = TaskContentStatus.SUCCESS
                    entry.error = null
                    // Gallery/indexing failure does not make a safely published output a failed conversion.
                    runCatching { MediaScannerConnection.scanFile(globalClass, arrayOf(requireNotNull(entry.destination).path), null, null) }
                } catch (error: CancellationException) {
                    entry.error = "Cancelled before output was published"
                    wasCancelled = true
                    break
                } catch (error: Exception) {
                    logger.logError(error)
                    entry.status = TaskContentStatus.FAILED
                    entry.error = error.message ?: "Conversion failed"
                } finally {
                    stage?.let { partial ->
                        if (partial.exists() && !partial.delete() && partial.exists()) {
                            entry.error = listOfNotNull(entry.error, "Partial output retained at ${partial.path}").joinToString(". ")
                        }
                    }
                    globalClass.taskManager.journal.saveItem(id, entry.record())
                }
                processed++
                progressMonitor.remainingContent = entries.size - processed
                progressMonitor.progress = processed.toFloat() / entries.size
            }
        } catch (_: CancellationException) {
            wasCancelled = true
        } catch (error: Exception) {
            logger.logError(error)
            preparationError = error.message ?: "Unable to prepare conversion"
            entries.filter { it.status != TaskContentStatus.SUCCESS }.forEach {
                it.status = TaskContentStatus.FAILED
                it.error = preparationError
            }
        }
        progressMonitor.status = if (entries.isEmpty() && preparationError != null) TaskStatus.FAILED
            else copyOutcome(entries.map { it.status }, wasCancelled)
        progressMonitor.remainingContent = entries.count { it.status == TaskContentStatus.PENDING }
        if (!wasCancelled) progressMonitor.progress = 1f
        progressMonitor.processName = if (wasCancelled) "Conversion cancelled" else "Conversion finished"
        progressMonitor.summary = buildString {
            val succeeded = entries.count { it.status == TaskContentStatus.SUCCESS }
            val failed = entries.count { it.status == TaskContentStatus.FAILED }
            append("$succeeded converted, $failed failed")
            if (wasCancelled) append(", ${entries.size - succeeded - failed} remaining")
            append(". Originals kept.")
            parameters?.destinationPath?.let { append("\nOutput folder: $it") }
            if (entries.isEmpty()) preparationError?.let { append("\n$it") }
            entries.forEach { entry ->
                append("\n")
                append(entry.source.name)
                append(when (entry.status) {
                    TaskContentStatus.SUCCESS -> " → ${entry.destination?.name}"
                    TaskContentStatus.FAILED -> ": ${entry.error}"
                    else -> ": ${entry.error ?: "not converted"}"
                })
            }
        }
        checkpoint()
    }
}
