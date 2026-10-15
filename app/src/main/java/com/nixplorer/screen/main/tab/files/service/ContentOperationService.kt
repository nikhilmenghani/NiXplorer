package com.nixplorer.screen.main.tab.files.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.App.Companion.logger
import com.nixplorer.R
import com.nixplorer.screen.main.MainActivity
import com.nixplorer.screen.main.tab.files.task.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ContentOperationService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Tasks ids and whether they are running
    private val runningTaskIds = ConcurrentHashMap<String, Boolean>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.task_running_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeTasksCount = runningTaskIds.size
        val userActionRequired = runningTaskIds.values.any { !it }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(globalClass.getString(R.string.tasks))
            .setContentText(
                if (userActionRequired) getString(R.string.action_required)
                else globalClass.getString(R.string.task_running_notification_text)
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setNumber(activeTasksCount)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { intent ->
            when (intent.action) {
                ACTION_START_TASK -> {
                    val taskId = intent.getStringExtra(TASK_ID)
                    if (taskId != null) {
                        startTask(taskId)
                    }
                }

                ACTION_REMOVE_TASK -> {
                    val taskId = intent.getStringExtra(TASK_ID)
                    if (taskId != null) {
                        removeTask(taskId)
                    }
                }
            }
        }
        if (intent == null && runningTaskIds.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun removeTask(taskId: String) {
        if (runningTaskIds.containsKey(taskId)) {
            if (runningTaskIds[taskId] == true) {
                globalClass.showMsg(R.string.task_is_running)
            } else {
                runningTaskIds.remove(taskId)
                updateNotification()


                // Stop service if no more tasks
                if (runningTaskIds.isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    private fun startTask(taskId: String) {
        if (runningTaskIds.containsKey(taskId) && runningTaskIds[taskId] == true) {
            return
        }

        val task = globalClass.taskManager.getTask(taskId)

        if (task == null) {
            if (runningTaskIds.isEmpty()) stopSelf()
            return
        }
        if (!globalClass.taskManager.claimDispatch(taskId)) return

        runningTaskIds[taskId] = true
        updateNotification()

        serviceScope.launch {
            try {
                globalClass.taskManager.executionMutex.withLock {
                    task.checkpoint()
                    if (task.getCurrentStatus() == TaskStatus.PENDING) task.run() else task.continueTask()
                    task.checkpoint()
                }
                globalClass.taskManager.handleTaskStatusChange(task, task.getCurrentStatus())
            } catch (e: kotlinx.coroutines.CancellationException) {
                task.progressMonitor.status = TaskStatus.CANCELLED
                task.progressMonitor.summary = "Interrupted; review retained sources and destination before retrying"
                task.checkpoint()
                globalClass.taskManager.handleTaskStatusChange(task, TaskStatus.CANCELLED)
            } catch (e: Exception) {
                task.progressMonitor.status = TaskStatus.FAILED
                task.progressMonitor.summary = e.message ?: "Operation failed"
                logger.logError(e)
                globalClass.taskManager.handleTaskStatusChange(task, TaskStatus.FAILED)
            } finally {
                globalClass.taskManager.releaseDispatch(taskId)
                globalClass.mainActivityManager.resumeActiveTab()
                if (task.getCurrentStatus() == TaskStatus.SUCCESS) {
                    runningTaskIds.remove(taskId)
                } else {
                    // require action from user
                    runningTaskIds[taskId] = false
                }

                updateNotification()
                getSystemService(NotificationManager::class.java).notify(taskId.hashCode(),
                    NotificationCompat.Builder(this@ContentOperationService, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(task.metadata.title + ": " + task.getCurrentStatus().name.replace('_', ' '))
                        .setContentText(task.progressMonitor.summary.take(160)).setStyle(NotificationCompat.BigTextStyle().bigText(task.progressMonitor.summary.take(2000)))
                        .setAutoCancel(true).build())

                // Stop service if no more tasks
                if (runningTaskIds.isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runningTaskIds.keys.forEach { id -> globalClass.taskManager.getTask(id)?.abortTask() }
        stopSelf() // Existing checkpoints reconcile interrupted work; Android's data-sync timeout must not crash the app.
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Cancel all running tasks
        runningTaskIds.keys.forEach { taskId ->
            globalClass.taskManager.getTask(taskId)?.abortTask()
        }

        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TASK_ID = "taskId"
        private const val NOTIFICATION_ID = 1297
        private const val CHANNEL_ID = "task_background_service"
        const val ACTION_START_TASK = "ACTION_START_TASK"
        const val ACTION_REMOVE_TASK = "ACTION_REMOVE_TASK"

        fun startNewBackgroundTask(context: Context, taskId: String) {
            val intent = Intent(context, ContentOperationService::class.java).apply {
                action = ACTION_START_TASK
                putExtra(TASK_ID, taskId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun removeBackgroundTask(context: Context, taskId: String) {
            val intent = Intent(context, ContentOperationService::class.java).apply {
                action = ACTION_REMOVE_TASK
                putExtra(TASK_ID, taskId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}