package com.nixplorer.screen.main.tab.files.misc

import com.nixplorer.screen.main.tab.files.misc.SortingMethod.SORT_BY_NAME

data class FileSortingPrefs(
    val sortMethod: Int = SORT_BY_NAME,
    val showFoldersFirst: Boolean = true,
    val reverseSorting: Boolean = false,
    val applyForThisFileOnly: Boolean = true,
    val directionMask: Int = 0,
    val configuredDirections: Int = 0
) {
    fun reversedFor(method: Int): Boolean = if (configuredDirections and (1 shl method) != 0)
        directionMask and (1 shl method) != 0 else method == sortMethod && reverseSorting
}
