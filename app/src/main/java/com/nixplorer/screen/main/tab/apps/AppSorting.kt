package com.nixplorer.screen.main.tab.apps

import com.nixplorer.screen.main.tab.apps.holder.AppHolder

internal fun sortApps(
    apps: List<AppHolder>,
    option: AppsTab.SortOption,
    ascending: Boolean,
    lastOpened: Map<String, Long>
): List<AppHolder> {
    val comparator = when (option) {
        AppsTab.SortOption.NAME -> compareBy<AppHolder> { it.name.lowercase() }
        AppsTab.SortOption.SIZE -> compareBy { it.size }
        AppsTab.SortOption.INSTALL_DATE -> compareBy { it.installDate }
        AppsTab.SortOption.UPDATE_DATE -> compareBy { it.lastUpdateDate }
        // Missing history is grouped before recorded use in oldest-first, after it in newest-first.
        AppsTab.SortOption.LAST_OPENED -> compareBy { lastOpened[it.packageName] ?: 0L }
    }
    return apps.sortedWith((if (ascending) comparator else comparator.reversed())
        .thenBy { it.name.lowercase() }.thenBy { it.packageName })
}
