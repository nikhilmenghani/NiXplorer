package com.nixplorer.screen.main.tab.files.task

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException

data class OperationItemRecord(val source: String, val destination: String, val status: String,
    val error: String?, val copied: Boolean, val sourceDeleted: Boolean, val identity: SafeFileOperations.Identity?)
data class OperationRecord(val id: String, val title: String, val kind: String, val sources: List<String>,
    val destination: String?, val status: String, val summary: String, val items: List<OperationItemRecord> = emptyList())

/** Durable checkpoints are evidence for reconciliation, never instructions to replay an unlink. */
class OperationJournal(private val directory: File) {
    @Synchronized fun save(record: OperationRecord) {
        SafeFileOperations.directory(directory)
        require(record.id.matches(Regex("[A-Za-z0-9-]+")))
        val stage = File.createTempFile(".checkpoint-", ".json", directory)
        try {
            stage.outputStream().use { it.write(Gson().toJson(record).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            val target = File(directory, "${record.id}.json")
            try { Files.move(stage.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) {
                // App-private storage normally supports atomic rename; fail closed if it does not.
                throw java.io.IOException("Durable task checkpoints require atomic app-storage rename")
            }
            if (record.items.isNotEmpty()) File(directory, "${record.id}.items.jsonl").delete()
        } finally { stage.delete() }
    }
    @Synchronized fun saveItem(id: String, item: OperationItemRecord) {
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        SafeFileOperations.directory(directory)
        java.io.FileOutputStream(File(directory, "$id.items.jsonl"), true).use {
            it.write((Gson().toJson(item) + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync()
        }
    }
    @Synchronized fun load(): List<OperationRecord> = directory.listFiles()?.filter { it.name.matches(Regex("[A-Za-z0-9-]+\\.json")) }?.map { file ->
        try {
            val record = requireNotNull(Gson().fromJson(file.readText(), OperationRecord::class.java))
            val items = LinkedHashMap<String, OperationItemRecord>()
            record.items.forEach { items[it.source + "\u0000" + it.destination] = it }
            val log = File(directory, "${record.id}.items.jsonl")
            if (log.isFile) log.useLines { lines -> lines.forEach { line ->
                // An interrupted final append can be incomplete. Earlier synced records remain valid evidence.
                val item = runCatching { Gson().fromJson(line, OperationItemRecord::class.java) }.getOrNull()
                if (item != null) {
                    val key = item.source + "\u0000" + item.destination
                    if (key !in items && items.size >= SafeFileOperations.MAX_ITEMS) throw java.io.IOException("Item checkpoint limit exceeded")
                    items[key] = item
                }
            } }
            record.copy(items = items.values.toList())
        }
        catch (e: Exception) { OperationRecord(file.nameWithoutExtension, "Interrupted operation", "unknown", emptyList(), null,
            "FAILED", "Unreadable checkpoint retained at $file: ${e.message}") }
    } ?: emptyList()
    @Synchronized fun remove(id: String) {
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        File(directory, "$id.json").delete(); File(directory, "$id.items.jsonl").delete()
    }
}

/** Resolving one task never replaces or resolves another task's pending conflict. */
class ConflictQueue<T> {
    private val entries = LinkedHashMap<String, T>()
    @Synchronized fun put(taskId: String, value: T) { entries[taskId] = value }
    @Synchronized fun first(): Pair<String, T>? = entries.entries.firstOrNull()?.let { it.key to it.value }
    @Synchronized fun remove(taskId: String): T? = entries.remove(taskId)
}

fun copyOutcome(statuses: List<TaskContentStatus>, cancelled: Boolean = false): TaskStatus = when {
    cancelled -> TaskStatus.CANCELLED
    statuses.any { it == TaskContentStatus.FAILED } -> if (statuses.any { it == TaskContentStatus.SUCCESS }) TaskStatus.PARTIAL_FAILURE else TaskStatus.FAILED
    statuses.any { it == TaskContentStatus.PENDING || it == TaskContentStatus.CONFLICT } -> TaskStatus.FAILED
    else -> TaskStatus.SUCCESS
}
