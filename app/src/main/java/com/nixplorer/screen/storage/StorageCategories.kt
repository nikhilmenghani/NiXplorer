package com.nixplorer.screen.storage

import com.nixplorer.screen.main.tab.files.misc.FileMimeType
import java.io.File
import java.io.Serializable

internal enum class StorageCategory { VIDEOS, IMAGES, DOCUMENTS, APKS, AUDIO, ARCHIVES, OTHER }
internal data class CategoryStorage(val category: StorageCategory, val bytes: Long, val count: Long,
    val largest: List<StorageEntry>) : Serializable

internal fun storageCategory(file: File): StorageCategory {
    val extension = file.extension.lowercase(java.util.Locale.ROOT)
    return when {
        extension in setOf("apk", "apks", "xapk", "apkm") -> StorageCategory.APKS
        extension in FileMimeType.videoFileType -> StorageCategory.VIDEOS
        extension in FileMimeType.imageFileType -> StorageCategory.IMAGES
        extension in FileMimeType.audioFileType -> StorageCategory.AUDIO
        extension in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "csv", "md", "epub", "mobi") -> StorageCategory.DOCUMENTS
        extension in FileMimeType.archiveFileType -> StorageCategory.ARCHIVES
        else -> StorageCategory.OTHER
    }
}
