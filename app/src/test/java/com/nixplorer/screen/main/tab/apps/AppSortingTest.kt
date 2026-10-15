package com.nixplorer.screen.main.tab.apps

import com.nixplorer.screen.main.tab.apps.holder.AppHolder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class AppSortingTest {
    private fun app(name: String, value: Long) = AppHolder(name, name, "", "1", 1, value,
        false, Date(value), Date(value), 35, 26, emptyList(), "", "", 1, true, false)

    @Test fun everyMethodSupportsBothDirections() {
        val apps = listOf(app("C", 3), app("A", 1), app("B", 2))
        val usage = apps.associate { it.packageName to it.size }
        AppsTab.SortOption.entries.forEach { option ->
            assertEquals(listOf("A", "B", "C"), sortApps(apps, option, true, usage).map { it.name })
            assertEquals(listOf("C", "B", "A"), sortApps(apps, option, false, usage).map { it.name })
        }
    }

    @Test fun missingUsageDoesNotAppearAsMostRecentlyOpened() {
        val apps = listOf(app("Unknown", 0), app("Recent", 3), app("Older", 1))
        val usage = mapOf("Recent" to 3L, "Older" to 1L)
        assertEquals(listOf("Unknown", "Older", "Recent"),
            sortApps(apps, AppsTab.SortOption.LAST_OPENED, true, usage).map { it.name })
        assertEquals(listOf("Recent", "Older", "Unknown"),
            sortApps(apps, AppsTab.SortOption.LAST_OPENED, false, usage).map { it.name })
    }
}
