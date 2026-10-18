package com.nixplorer.screen.main.tab.files.search

import org.junit.Assert.*
import org.junit.Test

class SearchRelevanceTest {
    private val tokenizer = WordPieceTokenizer(listOf("[UNK]", "[CLS]", "[SEP]", "nik", "##ga", "##pp", "##s", "app", "documents", "about", "income", "tax", "taxes", "mortgage", "payments"))
    @Test fun brandNameCannotBeReplacedByGenericAppSimilarity() {
        val terms = SearchRelevance.requiredTerms("NikGapps app", tokenizer)
        assertEquals(listOf("nikgapps"), terms)
        assertFalse(SearchRelevance.containsRequired("Mobile application privacy and billing", terms))
        assertTrue(SearchRelevance.containsRequired("NikGapps-debug.apk", terms))
        assertTrue(SearchRelevance.containsRequired("Documentation for the NIKGAPPS Android project", terms))
    }
    @Test fun lowercaseUnusualNamesAlsoRequireLiteralEvidence() {
        assertEquals(listOf("nikgapps"), SearchRelevance.requiredTerms("nikgapps app", tokenizer))
    }
    @Test fun ordinaryTopicsKeepSemanticMatching() {
        assertTrue(SearchRelevance.requiredTerms("documents about income tax", tokenizer).isEmpty())
        assertTrue(SearchRelevance.requiredTerms("mortgage payments", tokenizer).isEmpty())
    }
}
