package com.nixplorer.screen.main.tab.files.search

import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/** Read-only, explicit English search grammar. Unsupported syntax is never turned into an action. */
internal data class SmartQuery(
    val extensions: Set<String> = emptySet(),
    val foldersOnly: Boolean = false,
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val name: String? = null,
    val topic: String? = null,
    val summary: String = ""
) {
    fun matches(name: String, extension: String, folder: Boolean, bytes: Long, modified: Long): Boolean =
        (!foldersOnly || folder) && (extensions.isEmpty() || !folder && extension.lowercase() in extensions) &&
            (minBytes == null || bytes > minBytes) && (maxBytes == null || bytes < maxBytes) &&
            (modifiedAfter == null || modified >= modifiedAfter) && (modifiedBefore == null || modified < modifiedBefore) &&
            (this.name == null || name.contains(this.name, true))
}

internal object SmartQueryParser {
    private val types = linkedMapOf(
        "videos?" to setOf("mp4", "mkv", "mov", "avi", "webm", "ts", "m4v", "3gp", "wmv"),
        "(?:images?|photos?|pictures?)" to setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "svg"),
        "(?:documents?|docs?)" to setOf("pdf", "txt", "md", "docx", "csv", "json", "xml", "config", "rtf", "doc", "odt"),
        "(?:music|audio)" to setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac"),
        "(?:apks?|android apps?)" to setOf("apk"), "pdfs?" to setOf("pdf"),
        "(?:archives?|zips?)" to setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2"),
        "(?:text files?|texts?)" to setOf("txt", "md", "config", "conf", "log")
    )
    fun parse(input: String, clock: Clock = Clock.systemDefaultZone()): SmartQuery {
        require(input.length in 1..1000) { "Enter a query of at most 1,000 characters" }
        var remaining = input.trim().lowercase(Locale.ROOT)
        require(remaining.isNotBlank()) { "Enter a search query" }
        require(!Regex("\\b(delete|remove|move|rename|install|send|upload)\\b").containsMatchIn(remaining)) {
            "Smart search only finds files. Use the file actions to change them."
        }
        require(!Regex("\\b(or|except|excluding)\\b").containsMatchIn(remaining)) { "Use one file type and AND filters per search" }
        val summary = mutableListOf<String>()
        var topic: String? = null
        val topicMatch = Regex("\\b(?:about|containing|related to|contents? about)\\s+(.+)$").find(remaining)
        if (topicMatch != null) { topic = topicMatch.groupValues[1].trim(' ', '\'', '"'); remaining = remaining.substring(0, topicMatch.range.first) }
        var name: String? = null
        Regex("\\b(?:named|called|name contains)\\s+[\"']?(.+?)[\"']?$").find(remaining)?.let {
            name = it.groupValues[1].trim(' ', '\'', '"'); remaining = remaining.substring(0, it.range.first)
        }
        var min: Long? = null; var max: Long? = null
        val size = Regex("\\b(larger than|bigger than|over|above|smaller than|less than|under|below|at least|at most)\\s+(\\d+(?:\\.\\d+)?)\\s*(kb|mb|gb|tb|bytes?|kib|mib|gib)\\b")
        remaining = size.replace(remaining) { m ->
            val factor = when (m.groupValues[3]) { "kb", "kib" -> 1024.0; "mb", "mib" -> 1048576.0; "gb", "gib" -> 1073741824.0; "tb" -> 1099511627776.0; else -> 1.0 }
            val value = m.groupValues[2].toDouble() * factor
            require(value.isFinite() && value < Long.MAX_VALUE) { "Size is too large" }
            val bytes = value.toLong()
            when (m.groupValues[1]) {
                "under", "below", "smaller than", "less than" -> max = minOf(max ?: Long.MAX_VALUE, bytes)
                "at most" -> max = minOf(max ?: Long.MAX_VALUE, bytes + 1)
                "at least" -> min = maxOf(min ?: -1, bytes - 1)
                else -> min = maxOf(min ?: -1, bytes)
            }; " "
        }
        if (Regex("\\b(?:large|big)\\b").containsMatchIn(remaining)) { min = maxOf(min ?: 0, 100L * 1024 * 1024); remaining = remaining.replace(Regex("\\b(?:large|big)\\b"), " "); summary += "Large = over 100 MiB" }
        val zone = clock.zone; val today = LocalDate.now(clock)
        fun start(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        var after: Long? = null; var before: Long? = null
        val date = Regex("\\b(?:modified|updated|from|in|during)?\\s*(today|yesterday|this week|last week|this month|last month|last (\\d+) days?|older than (\\d+) days?|(?:after|before) \\d{4}-\\d{2}-\\d{2})\\b")
        remaining = date.replace(remaining) { m ->
            val text = m.groupValues[1]
            val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            when {
                text == "today" -> after = start(today)
                text == "yesterday" -> { after = start(today.minusDays(1)); before = start(today) }
                text == "this week" -> after = start(monday)
                text == "last week" -> { after = start(monday.minusWeeks(1)); before = start(monday) }
                text == "this month" -> after = start(today.withDayOfMonth(1))
                text == "last month" -> { after = start(today.withDayOfMonth(1).minusMonths(1)); before = start(today.withDayOfMonth(1)) }
                text.startsWith("last ") -> after = start(today.minusDays(m.groupValues[2].toLong()))
                text.startsWith("older than") -> before = start(today.minusDays(m.groupValues[3].toLong()))
                text.startsWith("after ") -> after = start(runCatching { LocalDate.parse(text.substringAfter(' ')) }.getOrElse { throw IllegalArgumentException("Use a valid date: YYYY-MM-DD") }.plusDays(1))
                else -> before = start(runCatching { LocalDate.parse(text.substringAfter(' ')) }.getOrElse { throw IllegalArgumentException("Use a valid date: YYYY-MM-DD") })
            }; " "
        }
        var extensions = emptySet<String>()
        for ((pattern, values) in types) {
            val match = Regex("\\b$pattern\\b").find(remaining) ?: continue
            val combined = if (extensions.isEmpty()) values else extensions.intersect(values)
            require(combined.isNotEmpty()) { "Choose one file type per query" }
            extensions = combined; remaining = remaining.removeRange(match.range); summary += match.value
        }
        Regex("\\*\\.([a-z0-9]+)").find(remaining)?.let { extensions = setOf(it.groupValues[1]); summary += "*.${it.groupValues[1]}"; remaining = remaining.removeRange(it.range) }
        val folders = Regex("\\bfolders?\\b").containsMatchIn(remaining)
        remaining = remaining.replace(Regex("\\b(?:find|show|me|my|all|the|files?|folders?|and|please|that|are|were|have been|modified|updated)\\b"), " ").trim()
        require(min == null || max == null || min!! < max!!) { "Size filters conflict" }
        require(after == null || before == null || after!! < before!!) { "Date filters conflict" }
        // A bare phrase is a semantic name/content query, not a fabricated filename filter.
        require(!folders || min == null && max == null) { "Folder-size search is not supported; search file sizes instead" }
        require(topic == null || topic!!.isNotBlank()) { "Enter a subject after about" }
        if (remaining.isNotBlank()) {
            require(!Regex("\\b(over|under|above|below|before|after|from|last|between)\\b").containsMatchIn(remaining)) {
                "Unrecognized filter. Try: over 100 MB, last week, or after 2026-01-01"
            }
            require(topic == null && name == null) { "Unrecognized filter: $remaining" }
            topic = remaining.replace(Regex("\\s+"), " ")
        }
        name?.let { summary += "Name contains: $it" }; topic?.let { summary += "Meaning: $it" }
        min?.let { summary += "Size > $it bytes" }; max?.let { summary += "Size < $it bytes" }
        after?.let { summary += "Modified since ${java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}" }
        before?.let { summary += "Modified before ${java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}" }
        if (folders) summary += "Folders"
        require(summary.isNotEmpty()) { "Try: videos over 100 MB, PDFs from last week, or documents about taxes" }
        return SmartQuery(extensions, folders, min, max, after, before, name, topic, summary.joinToString(" \u00b7 "))
    }
}
