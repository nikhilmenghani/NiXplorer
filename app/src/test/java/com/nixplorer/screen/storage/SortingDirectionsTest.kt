package com.nixplorer.screen.storage

import com.nixplorer.screen.main.tab.files.misc.FileSortingPrefs
import com.nixplorer.screen.main.tab.files.misc.SortingMethod
import org.junit.Assert.*
import org.junit.Test

class SortingDirectionsTest {
    @Test fun dateDirectionDoesNotReverseSize() {
        val prefs = FileSortingPrefs(sortMethod = SortingMethod.SORT_BY_DATE, reverseSorting = true,
            directionMask = 1 shl SortingMethod.SORT_BY_DATE, configuredDirections = 30)
        assertTrue(prefs.reversedFor(SortingMethod.SORT_BY_DATE))
        assertFalse(prefs.reversedFor(SortingMethod.SORT_BY_SIZE))
        val switched = prefs.copy(sortMethod = SortingMethod.SORT_BY_SIZE, reverseSorting = false)
        assertTrue(switched.reversedFor(SortingMethod.SORT_BY_DATE))
        assertFalse(switched.reversedFor(SortingMethod.SORT_BY_SIZE))
    }
    @Test fun legacyOrderOnlyAppliesToItsSelectedMethod() {
        val prefs = FileSortingPrefs(sortMethod = SortingMethod.SORT_BY_DATE, reverseSorting = true)
        assertTrue(prefs.reversedFor(SortingMethod.SORT_BY_DATE))
        assertFalse(prefs.reversedFor(SortingMethod.SORT_BY_SIZE))
    }
}
