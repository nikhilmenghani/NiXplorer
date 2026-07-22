package com.nixplorer.screen.main.tab.files.task

enum class TaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    PAUSED,
    CONFLICT
}

enum class TaskContentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    CONFLICT,
    SKIP,
    REPLACE,
    ASK
}