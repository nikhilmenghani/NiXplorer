package com.nixplorer.screen.main.tab.files.task

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.emptyString
import com.nixplorer.common.isNot
import com.nixplorer.common.showMsg
import com.nixplorer.screen.main.tab.files.service.ContentOperationService.Companion.startNewBackgroundTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class TaskManager {
    private val taskScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = java.util.concurrent.atomic.AtomicBoolean(false)

    val journal = OperationJournal(java.io.File(globalClass.filesDir, "operation-journal"))
    val executionMutex = kotlinx.coroutines.sync.Mutex()
    private val dispatching = ConcurrentHashMap.newKeySet<String>()
    fun claimDispatch(id: String) = dispatching.add(id)
    fun releaseDispatch(id: String) { dispatching.remove(id) }

    private val allTasks = ConcurrentHashMap<String, Task>()

    val pendingTasks: MutableCollection<Task> = Collections.synchronizedList(mutableListOf<Task>())
    val runningTasks: MutableCollection<Task> = Collections.synchronizedList(mutableListOf<Task>())
    val pausedTasks: MutableCollection<Task> = Collections.synchronizedList(mutableListOf<Task>())
    val failedTasks: MutableCollection<Task> = Collections.synchronizedList(mutableListOf<Task>())
    val invalidTasks: MutableCollection<Task> = Collections.synchronizedList(mutableListOf<Task>())
    val completedTasks: MutableCollection<Task> =
        Collections.synchronizedList(mutableListOf<Task>())

    @Volatile
    var runningTaskDialogInfo = RunningTaskDialogInfo()
    val taskInterceptor = TaskConflict()

    init {
        taskScope.launch {
            executionMutex.withLock {
            journal.load().filter { it.status != TaskStatus.SUCCESS.name }.forEach { record ->
                val destinations = (record.items.map { it.destination } + record.sources + listOfNotNull(record.destination)).map { it.substringBefore("!/") }.filter { it.startsWith("/") }
                val notes = destinations.distinct().flatMap { path -> runCatching { SafeFileOperations.reconcileReplacement(java.io.File(path)) }.getOrElse { listOf("Cannot reconcile $path: ${it.message}") } } +
                    record.items.filter { "!/" !in it.source }.flatMap { item -> runCatching { SafeFileOperations.reconcileRetainedSource(java.io.File(item.source), item.identity) }.getOrElse { listOf("Source recovery needs review: ${it.message}") } }
                val retainedStages = (record.items.map { it.destination } + record.sources + listOfNotNull(record.destination)).mapNotNull { path ->
                    path.substringBefore("!/").takeIf { it.startsWith("/") }?.let { java.io.File(it).parentFile } }
                    .distinct().flatMap { parent ->
                        if (!parent.isDirectory) emptyList() else runCatching { java.nio.file.Files.newDirectoryStream(parent.toPath()) { path ->
                            listOf(".nix-stage-", ".nix-archive-", ".nix-rename-", ".nix-compress-", ".nix-delete-").any { path.fileName.toString().startsWith(it + record.id + "-") }
                        }.use { entries ->
                            entries.map { "Retained partial write (review before removal): $it" }.take(SafeFileOperations.MAX_ITEMS).toList()
                        } }.getOrElse { listOf("Cannot inspect partial writes at $parent: ${it.message}") }
                    }
                val recovered = RecoveredTask(record.copy(summary = record.summary + "\n" + (notes + retainedStages).joinToString("\n")))
                if (allTasks.putIfAbsent(record.id, recovered) == null) failedTasks.add(recovered)
            }
            }
        }
    }

    fun addTask(
        task: Task,
        notifyUser: Boolean = true
    ) {
        allTasks[task.id] = task
        pendingTasks.add(task)
        if (notifyUser) globalClass.showMsg(globalClass.resources.getString(R.string.new_task_has_been_added))
    }

    fun addTaskAndRun(task: Task, parameters: TaskParameters) {
        addTask(task, false)
        runTask(task.id, parameters)
    }

    fun removeTask(id: String) {
        allTasks[id]?.abortTask()
        allTasks.remove(id)
        taskInterceptor.remove(id)
        taskScope.launch { journal.remove(id) }

        pendingTasks.removeIf { it.id == id }
        runningTasks.removeIf { it.id == id }
        pausedTasks.removeIf { it.id == id }
        failedTasks.removeIf { it.id == id }
        invalidTasks.removeIf { it.id == id }
        completedTasks.removeIf { it.id == id }
    }

    suspend fun validateTasks(onFinished: (() -> Unit)? = null) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val snapshot = synchronized(pendingTasks) { pendingTasks.toList() }
            snapshot.forEach { task ->
                if (!task.validate()) {
                    invalidTasks.add(task)
                    pendingTasks.removeIf { it.id == task.id }
                }
            }
        }
        onFinished?.invoke()
    }

    fun runTask(id: String, taskParameters: TaskParameters) {
        val task = allTasks[id]

        if (task == null) {
            showMsg(R.string.task_not_found)
            return
        }

        if (task.getCurrentStatus() isNot TaskStatus.PENDING) {
            showMsg(R.string.task_is_not_in_pending_state)
            return
        }

        moveTaskToRunning(task)
        task.setParameters(taskParameters)
        bringToForeground(task)
        startNewBackgroundTask(globalClass, task.id)
    }

    fun continueTask(taskId: String) {
        val task = allTasks[taskId]
        if (task == null) {
            showMsg(R.string.task_not_found)
            return
        }

        if (task is RecoveredTask) { showMsg(task.progressMonitor.summary); return }
        val currentStatus = task.getCurrentStatus()

        if (currentStatus isNot TaskStatus.CONFLICT
            && currentStatus isNot TaskStatus.FAILED
            && currentStatus isNot TaskStatus.PARTIAL_FAILURE
            && currentStatus isNot TaskStatus.CANCELLED
            && currentStatus isNot TaskStatus.PAUSED
        ) {
            showMsg(
                globalClass.getString(
                    R.string.task_cannot_be_continued_from_current_state,
                    currentStatus
                )
            )
            return
        }

        task.aborted = false // Explicit user retry/resolution authorizes resuming; queued cancellation is otherwise preserved.
        // Move task back to running
        pausedTasks.removeIf { it.id == taskId }
        failedTasks.removeIf { it.id == taskId }

        if (!runningTasks.any { it.id == taskId }) {
            runningTasks.add(task)
        }

        bringToForeground(task)
        startNewBackgroundTask(globalClass, task.id)
    }

    private fun moveTaskToRunning(task: Task) {
        pendingTasks.removeIf { it.id == task.id }
        pausedTasks.removeIf { it.id == task.id }
        failedTasks.removeIf { it.id == task.id }

        if (!runningTasks.any { it.id == task.id }) {
            runningTasks.add(task)
        }
    }

    fun handleTaskStatusChange(task: Task, newStatus: TaskStatus) {
        pausedTasks.removeIf { it.id == task.id }
        failedTasks.removeIf { it.id == task.id }
        when (newStatus) {
            TaskStatus.SUCCESS -> {
                runningTasks.removeIf { it.id == task.id }
                completedTasks.add(task)
                taskScope.launch { journal.remove(task.id) }
                showMsg(R.string.task_completed)
            }

            TaskStatus.FAILED, TaskStatus.PARTIAL_FAILURE -> {
                runningTasks.removeIf { it.id == task.id }
                failedTasks.add(task)
                showMsg(R.string.task_failed)
            }

            TaskStatus.PAUSED, TaskStatus.CANCELLED -> {
                runningTasks.removeIf { it.id == task.id }
                pausedTasks.add(task)
                showMsg(R.string.task_paused)
            }

            TaskStatus.CONFLICT -> {
                runningTasks.removeIf { it.id == task.id }
                pausedTasks.add(task)
                // Conflict will be handled by the UI
            }

            else -> {
                // No action needed for other states
            }
        }
    }

    fun getTask(id: String): Task? = allTasks[id]

    fun overrideConflicts(taskId: String, resolution: TaskContentStatus) {
        allTasks[taskId]?.overrideConflicts(resolution)
    }

    fun bringToForeground(task: Task) {
        if (isMonitoring.compareAndSet(false, true)) {
            taskScope.launch {
                monitorRunningTask(task)
            }
        }
    }

    private suspend fun monitorRunningTask(task: Task) {
        runningTaskDialogInfo.show(task)
        try {
            while (runningTaskDialogInfo.showDialog) {
                if (!runningTasks.contains(task)) {
                    break
                }

                runningTaskDialogInfo.updateInfo(task.progressMonitor)
                delay(100)
            }
        } finally {
            isMonitoring.set(false)
            runningTaskDialogInfo.hide()
        }
    }

    fun hideRunningTaskDialog() {
        runningTaskDialogInfo.hide()
    }

    class TaskConflict {
        private val queue = ConflictQueue<Pair<TaskContentItem, Task>>()
        var taskContentItem by mutableStateOf<TaskContentItem?>(null)
            private set
        var task by mutableStateOf<Task?>(null)
            private set
        val hasConflict get() = task != null
        val message get() = taskContentItem?.let {
            globalClass.resources.getString(R.string.file_already_exists, it.content.displayName)
        } ?: emptyString

        @Synchronized private fun refresh() {
            val entry = queue.first()?.second
            taskContentItem = entry?.first
            task = entry?.second
        }
        @Synchronized fun interceptTask(item: TaskContentItem, owner: Task) {
            owner.checkpoint()
            queue.put(owner.id, item to owner)
            refresh()
        }
        @Synchronized fun remove(taskId: String) { queue.remove(taskId); refresh() }
        fun hide() {
            val current = task ?: return
            current.abortTask()
            current.progressMonitor.status = TaskStatus.CANCELLED
            current.progressMonitor.summary = "Cancelled while waiting for a conflict decision"
            current.checkpoint()
            remove(current.id)
            globalClass.taskManager.handleTaskStatusChange(current, TaskStatus.CANCELLED)
        }
        fun resolve(resolution: TaskContentStatus, applyToAllConflicts: Boolean = false) {
            val owner = task ?: return
            val item = taskContentItem ?: return
            item.status = resolution
            if (applyToAllConflicts) owner.overrideConflicts(resolution)
            remove(owner.id)
            globalClass.taskManager.continueTask(owner.id)
        }
    }

    class RunningTaskDialogInfo {
        var linkedTask by mutableStateOf<Task?>(null)
        var showDialog by mutableStateOf(false)
            private set
        var progressMonitor by mutableStateOf<TaskProgressMonitor?>(null)

        fun show(task: Task) {
            if (showDialog) return

            linkedTask = task
            showDialog = true
        }

        fun hide() {
            if (!showDialog) return

            showDialog = false
            reset()
        }

        private fun reset() {
            linkedTask = null
            progressMonitor = null
        }

        fun updateInfo(progressMonitor: TaskProgressMonitor) {
            this.progressMonitor = TaskProgressMonitor(
                status = progressMonitor.status,
                taskTitle = progressMonitor.taskTitle,
                processName = progressMonitor.processName,
                contentName = progressMonitor.contentName,
                totalContent = progressMonitor.totalContent,
                remainingContent = progressMonitor.remainingContent,
                progress = progressMonitor.progress,
                summary = progressMonitor.summary
            )
        }
    }
}
