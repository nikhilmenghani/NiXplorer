package com.nixplorer.screen.share

import java.io.File

internal fun sharedFileName(displayName: String?, fallback: String): String {
    val name = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl() || it in ":*?\"<>|") '_' else it }.joinToString("")
        .trim().take(160)
    return name.takeUnless { it.isBlank() || it == "." || it == ".." } ?: fallback
}

internal fun availableSharedName(folder: File, name: String): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    for (index in 0..10000) {
        val candidate = if (index == 0) name else name.take(dot) + " ($index)" + name.substring(dot)
        if (!java.nio.file.Files.exists(folder.resolve(candidate).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return candidate
    }
    throw java.io.IOException("Too many files with the same name")
}
