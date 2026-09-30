package com.nixplorer.screen.main.tab.files.search

import org.junit.Assert.*
import org.junit.Test
import java.util.regex.PatternSyntaxException

class SearchPatternTest {
    private fun matches(query: String, filename: String, ignoreCase: Boolean = true): Boolean =
        compileSearchPattern(query, false, ignoreCase)!!.matcher(filename).find()

    @Test fun apkWildcardsMatchCompleteFilenames() {
        assertTrue(matches("*.apk", "NiXplorer.apk"))
        assertTrue(matches("*.apk", "APP.APK"))
        assertFalse(matches("*.apk", "app.apk.backup"))
        assertFalse(matches("*.apk", "appXapk"))
        assertTrue(matches("N*.apk", "NiXplorer.apk"))
        assertTrue(matches("N**.apk", "NiXplorer.apk"))
        assertFalse(matches("N*.apk", "other.apk"))
        assertFalse(matches("N*.apk", "nix.apk", false))
    }

    @Test fun questionMarkAndLiteralRegexCharacters() {
        assertTrue(matches("N?.apk", "N1.apk"))
        assertFalse(matches("N?.apk", "N12.apk"))
        assertTrue(matches("N[1]+*.apk", "N[1]+release.apk"))
        assertFalse(matches("N[1]+*.apk", "N1release.apk"))
    }

    @Test fun plainSearchAndRegexRemainSeparate() {
        assertNull(compileSearchPattern("apk", false, true))
        assertTrue(compileSearchPattern(".*\\.apk$", true, true)!!.matcher("Nix.apk").find())
    }

    @Test(expected = PatternSyntaxException::class)
    fun invalidRegexIsNotSilentlyTreatedAsLiteral() {
        compileSearchPattern("*.apk", true, true)
    }
}
