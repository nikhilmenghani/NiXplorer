package com.nixplorer.screen.main.tab.files.search

/** Preserve names/identifiers rather than letting generic words such as "app" dominate them. */
internal object SearchRelevance {
    private val generic = setOf("app", "apps", "application", "applications", "android", "pdf", "pdfs",
        "apk", "apks", "file", "files", "document", "documents", "video", "videos", "image", "images",
        "find", "show", "please", "about", "with", "the", "my", "and", "from", "named", "called", "mb", "gb", "kb")
    fun requiredTerms(query: String, tokenizer: WordPieceTokenizer): List<String> {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toList()
        val appContext = words.any { it.lowercase() in setOf("app", "apps", "application") }
        return words.filter { word ->
            val lower = word.lowercase()
            word.length >= 3 && lower !in generic && word.any { it.isLetter() } && (
                word.drop(1).any { it.isUpperCase() } ||
                    appContext && word.first().isUpperCase() || tokenizer.isUnusualWord(word))
        }.map { it.lowercase() }.distinct().take(8)
    }
    fun containsRequired(text: String, terms: List<String>) = terms.all { text.contains(it, ignoreCase = true) }
}
