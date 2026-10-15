package com.nixplorer.screen.main.tab.files.task

import com.nixplorer.screen.main.tab.files.holder.ContentHolder

data class TaskContentItem(
    val content: ContentHolder,
    val relativePath: String,
    var error: String? = null,
    var sourceIdentity: SafeFileOperations.Identity? = null,
    var copied: Boolean = false,
    var sourceDeleted: Boolean = false,
    var status: TaskContentStatus
)