package com.nixplorer.screen.main.tab.files.conversion

import com.nixplorer.screen.main.tab.files.task.SafeFileOperations
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Plans names without reserving user-visible empty files. Publishing must still use no-replace. */
internal object ConversionOutputs {
    fun nextAvailable(source: File, directory: File, format: ConversionFormat, reserved: Set<String> = emptySet()): File =
        findAvailable(source, directory, format, reserved, 0).first

    /** Remember suffixes within a batch so repeated base names do not rescan every earlier collision. */
    class Planner(private val directory: File, private val format: ConversionFormat, existingOutputs: List<File>) {
        private val reserved = existingOutputs.map { it.path }.toMutableSet()
        private val nextIndices = mutableMapOf<String, Int>()

        fun next(source: File): File {
            val key = source.nameWithoutExtension
            val (file, index) = findAvailable(source, directory, format, reserved, nextIndices[key] ?: 0)
            reserved.add(file.path)
            nextIndices[key] = index + 1
            return file
        }
    }

    private fun findAvailable(source: File, directory: File, format: ConversionFormat,
                              reserved: Set<String>, startingAt: Int): Pair<File, Int> {
        val base = source.nameWithoutExtension.ifBlank { "converted" }
        for (index in startingAt..100_000) {
            val suffix = (if (index == 0) "" else " ($index)") + "." + format.extension
            var stem = base
            while ((stem + suffix).toByteArray(Charsets.UTF_8).size > 240) {
                stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
            }
            val candidate = SafeFileOperations.contained(directory, stem + suffix)
            if (candidate.path !in reserved && !Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return candidate to index
        }
        error("Too many files share this output name")
    }
}
