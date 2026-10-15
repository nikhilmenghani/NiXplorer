package com.nixplorer.screen.main.tab.files.task

import java.util.UUID

abstract class Task(
    val id: String = UUID.randomUUID().toString()
) {
    var commonConflictResolution = TaskContentStatus.ASK

    @Volatile
    var aborted = false

    /**
     * Used to protect task from the time it is added to the running list and gets actually run
     */
    var protect = false

    open fun overrideConflicts(resolution: TaskContentStatus) {
        commonConflictResolution = resolution
    }

    open fun abortTask() {
        aborted = true
    }

    open fun recoveryRecord() = OperationRecord(id, metadata.title, javaClass.simpleName, emptyList(), null,
        getCurrentStatus().name, progressMonitor.summary)
    fun checkpoint() { com.nixplorer.App.globalClass.taskManager.journal.save(recoveryRecord()) }

    abstract val metadata: TaskMetadata
    abstract val progressMonitor: TaskProgressMonitor

    abstract fun getCurrentStatus(): TaskStatus
    abstract fun setParameters(params: TaskParameters)

    abstract suspend fun validate(): Boolean
    abstract suspend fun run(params: TaskParameters)
    abstract suspend fun run()
    abstract suspend fun continueTask()
}