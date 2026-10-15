package com.nixplorer.screen.main.tab.files.zip

import com.nixplorer.screen.main.tab.files.task.SafeFileOperations
import java.io.File

object ArchiveEdits {
    /** One lock also covers archive writes outside background tasks. Conservative serialization avoids stale writers. */
    private val lock = Any()
    fun <T> coordinated(action: () -> T): T = synchronized(lock) { action() }
    fun update(original: File, cancelled: () -> Boolean = { false }, operationId: String? = null, edit: (File) -> Unit) = coordinated {
        ArchiveSafety.validate(original)
        val before = SafeFileOperations.identity(original, cancelled)
        require(operationId == null || operationId.matches(Regex("[A-Za-z0-9-]+")))
        val stage = File.createTempFile(".nix-archive-${operationId?.plus("-") ?: ""}", ".zip", original.parentFile)
        var replacementStarted = false
        try {
            original.inputStream().use { SafeFileOperations.stream(it, stage, Long.MAX_VALUE, cancelled) }
            edit(stage)
            ArchiveSafety.validate(stage, readData = true, cancelled = cancelled)
            SafeFileOperations.checkCancelled(cancelled)
            require(SafeFileOperations.identity(original, cancelled) == before) { "Archive changed during editing; original retained" }
            replacementStarted = true
            SafeFileOperations.replace(stage, original)
        } finally {
            // A replacement failure leaves uniquely named recovery material for inspection.
            if (!replacementStarted || !stage.exists()) stage.delete()
        }
    }
}
