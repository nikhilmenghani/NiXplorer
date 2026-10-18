package com.nixplorer.screen.main.tab.files.search

import java.text.Normalizer
import java.util.Locale

internal class WordPieceTokenizer(vocabulary: List<String>) {
    private val ids = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    fun isUnusualWord(word: String): Boolean {
        val lower = word.lowercase(Locale.ROOT)
        if (lower in ids || lower.length < 4) return false
        val encoded = encode(lower)
        return encoded.size >= 5 || ids.getValue("[UNK]") in encoded
    }

    fun encode(text: String): LongArray {
        val result = arrayListOf(ids.getValue("[CLS]"))
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        for (match in Regex("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]").findAll(normalized)) {
            val word = match.value
            val pieces = arrayListOf<Long>()
            var offset = 0
            if (word.length > 100) pieces += ids.getValue("[UNK]") else {
                while (offset < word.length) {
                    var end = word.length
                    var id: Long? = null
                    while (end > offset) {
                        id = ids[(if (offset > 0) "##" else "") + word.substring(offset, end)]
                        if (id != null) break
                        end--
                    }
                    if (id == null) { pieces.clear(); pieces += ids.getValue("[UNK]"); break }
                    pieces += id; offset = end
                }
            }
            for (id in pieces) { if (result.size >= 127) break; result += id }
            if (result.size >= 127) break
        }
        result += ids.getValue("[SEP]")
        return result.toLongArray()
    }
}
