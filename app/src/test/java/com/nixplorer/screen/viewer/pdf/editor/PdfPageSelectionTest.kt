package com.nixplorer.screen.viewer.pdf.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfPageSelectionTest {
    @Test fun preservesOrderRangesAndDuplicates() {
        assertEquals(listOf(2, 0, 1, 1), PdfPageSelection.parse("3, 1 - 2,2", 3))
    }
    @Test fun supportsReversedRanges() {
        assertEquals(listOf(4, 3, 2, 0), PdfPageSelection.parse("5-3,1", 5))
    }
    @Test fun acceptsAllOddAndEvenWithOneBasedMeaning() {
        assertEquals(listOf(0, 1, 2, 3, 4), PdfPageSelection.parse(" ALL ", 5))
        assertEquals(listOf(0, 2, 4), PdfPageSelection.parse("odd", 5))
        assertEquals(listOf(1, 3), PdfPageSelection.parse("even", 5))
    }
    @Test fun rejectsOutOfRangeZeroAndMalformedInputs() {
        for (input in listOf("", "0", "6", "1,", "1,,2", "1--2", "-1", "1.5", "99999999999999", "1-99999999999999", "odd,1")) {
            assertThrows(input, IllegalArgumentException::class.java) { PdfPageSelection.parse(input, 5) }
        }
    }
    @Test fun rejectsEmptyEvenSelection() {
        assertThrows(IllegalArgumentException::class.java) { PdfPageSelection.parse("even", 1) }
    }
    @Test fun limitsExpansionBeforeAnUnboundedListIsAllocated() {
        assertThrows(IllegalArgumentException::class.java) { PdfPageSelection.parse("1-200,1", 200) }
        assertThrows(IllegalArgumentException::class.java) { PdfPageSelection.parse("1", 0) }
    }
}
