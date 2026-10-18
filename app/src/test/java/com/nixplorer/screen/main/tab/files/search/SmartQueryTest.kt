package com.nixplorer.screen.main.tab.files.search

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

class SmartQueryTest {
    private val zone = ZoneId.of("America/Toronto")
    private val clock = Clock.fixed(Instant.parse("2026-10-15T16:00:00Z"), zone)
    private fun day(text: String) = LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli()
    @Test fun combinesTypeSizeAndCalendarDates() {
        val plan = SmartQueryParser.parse("show me videos larger than 100 MB from last week", clock)
        assertTrue("mp4" in plan.extensions)
        assertEquals(100L * 1024 * 1024, plan.minBytes)
        assertEquals(day("2026-10-05"), plan.modifiedAfter)
        assertEquals(day("2026-10-12"), plan.modifiedBefore)
        assertNull(plan.topic)
        assertTrue(plan.matches("movie.mp4", "mp4", false, 200L * 1024 * 1024, day("2026-10-08")))
        assertFalse(plan.matches("movie.mp4", "mp4", false, 50L * 1024 * 1024, day("2026-10-08")))
        assertFalse(plan.matches("movie.mp4", "mp4", false, 200L * 1024 * 1024, day("2026-10-13")))
    }
    @Test fun separatesContentTopicAndFilenameQueries() {
        val semantic = SmartQueryParser.parse("documents about income tax", clock)
        assertEquals("income tax", semantic.topic)
        assertTrue("pdf" in semantic.extensions)
        val named = SmartQueryParser.parse("files named Niktv", clock)
        assertEquals("niktv", named.name)
        assertNull(named.topic)
        assertTrue(named.matches("Niktv.json", "json", false, 10, 0))
    }
    @Test fun folderDatesAndSizeRanges() {
        val folder = SmartQueryParser.parse("folders modified yesterday", clock)
        assertTrue(folder.foldersOnly)
        assertEquals(day("2026-10-14"), folder.modifiedAfter)
        assertEquals(day("2026-10-15"), folder.modifiedBefore)
        val plan = SmartQueryParser.parse("APKs over 1 MB and under 20 MB", clock)
        assertEquals(setOf("apk"), plan.extensions)
        assertTrue(plan.matches("a.apk", "apk", false, 2L * 1024 * 1024, 0))
        assertFalse(plan.matches("a.apk", "apk", false, 21L * 1024 * 1024, 0))
    }
    @Test fun rejectsConflictsAndDestructiveOrAmbiguousQueries() {
        listOf("delete videos", "videos or images", "files over 1 GB under 100 MB", " ").forEach { text ->
            try { SmartQueryParser.parse(text, clock); fail("Accepted: $text") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun bareSubjectUsesMeaningAndLargeThresholdIsExplicit() {
        assertEquals("income tax", SmartQueryParser.parse("income tax", clock).topic)
        val plan = SmartQueryParser.parse("large videos", clock)
        assertEquals(100L * 1024 * 1024, plan.minBytes)
        assertTrue(plan.summary.contains("100 MiB"))
    }
}
