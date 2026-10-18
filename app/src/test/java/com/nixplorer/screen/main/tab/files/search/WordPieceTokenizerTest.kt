package com.nixplorer.screen.main.tab.files.search

import org.junit.Assert.*
import org.junit.Test

class WordPieceTokenizerTest {
    private val tokenizer = WordPieceTokenizer(listOf("[UNK]", "[CLS]", "[SEP]", "tax", "##es", "cafe", "."))
    @Test fun normalizesAccentsCaseAndWordPieces() {
        assertArrayEquals(longArrayOf(1, 3, 4, 5, 6, 2), tokenizer.encode("TAXES café."))
    }
    @Test fun boundsTokensAndHandlesUnknownWords() {
        assertArrayEquals(longArrayOf(1, 0, 2), tokenizer.encode("missing"))
        val tokens = tokenizer.encode("tax ".repeat(1000))
        assertEquals(128, tokens.size)
        assertEquals(1L, tokens.first()); assertEquals(2L, tokens.last())
    }
}
