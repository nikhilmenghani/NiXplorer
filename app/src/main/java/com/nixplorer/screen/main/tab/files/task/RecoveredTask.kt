package com.nixplorer.screen.main.tab.files.task

/** Interrupted destructive work needs review; retries never reconstruct deletes from path names alone. */
class RecoveredTask(private val record: OperationRecord) : Task(record.id) {
    override val metadata = TaskMetadata(id, "", record.title, "Interrupted — review required",
        record.sources.joinToString("\n"), record.summary, false, false)
    override val progressMonitor = TaskProgressMonitor(status = TaskStatus.FAILED, taskTitle = record.title,
        summary = buildString {
            appendLine("Interrupted ${record.kind}; destructive steps were not replayed.")
            appendLine("Sources: ${record.sources.joinToString()}")
            appendLine("Destination: ${record.destination ?: "not recorded"}")
            record.items.forEach { appendLine("${it.source} -> ${it.destination}: ${it.status}; copied=${it.copied}, sourceDeleted=${it.sourceDeleted}; ${it.error ?: ""}") }
            appendLine("Review the destination and retained .nix staging/backup files before starting a new operation.")
            append(record.summary)
        })
    override fun getCurrentStatus() = progressMonitor.status
    override fun setParameters(params: TaskParameters) = Unit
    override suspend fun validate() = false
    override suspend fun run(params: TaskParameters) = Unit
    override suspend fun run() = Unit
    override suspend fun continueTask() { com.nixplorer.common.showMsg(progressMonitor.summary) }
    override fun recoveryRecord() = record
}
