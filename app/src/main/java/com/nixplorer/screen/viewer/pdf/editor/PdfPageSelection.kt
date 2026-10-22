package com.nixplorer.screen.viewer.pdf.editor

/** User-facing numbers are one-based. Output order and intentional duplicates are retained. */
internal object PdfPageSelection {
    fun parse(input: String, pageCount: Int): List<Int> {
        require(pageCount in 1..200) { "The PDF must contain between 1 and 200 pages." }
        val value = input.trim().lowercase(java.util.Locale.ROOT)
        require(value.isNotEmpty()) { "Enter page numbers, for example 1-3, 5." }
        when (value) {
            "all" -> return (0 until pageCount).toList()
            "odd" -> return (0 until pageCount step 2).toList()
            "even" -> return (1 until pageCount step 2).toList().also {
                require(it.isNotEmpty()) { "This document has no even-numbered pages." }
            }
        }
        val result = mutableListOf<Int>()
        for (part in value.split(',')) {
            val token = part.trim()
            val range = Regex("([0-9]+)\\s*-\\s*([0-9]+)").matchEntire(token)
            val first = (range?.groupValues?.get(1) ?: token).toIntOrNull()
            val last = if (range != null) range.groupValues[2].toIntOrNull() else first
            require(first != null && last != null && first in 1..pageCount && last in 1..pageCount) {
                "Use page numbers between 1 and $pageCount, separated by commas or ranges."
            }
            val step = if (first <= last) 1 else -1
            var page = first
            while (true) {
                require(result.size < 200) { "Keep at most 200 pages, including duplicates." }
                result += page - 1
                if (page == last) break
                page += step
            }
        }
        return result
    }
}
