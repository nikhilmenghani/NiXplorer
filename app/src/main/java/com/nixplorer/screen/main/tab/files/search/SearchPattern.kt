package com.nixplorer.screen.main.tab.files.search

import java.util.regex.Pattern

internal fun compileSearchPattern(query: String, useRegex: Boolean, ignoreCase: Boolean): Pattern? {
    val expression = when {
        useRegex -> query
        query.any { it == '*' || it == '?' } -> buildString {
            append("^")
            query.forEach { character ->
                when (character) {
                    '*' -> append(".*")
                    '?' -> append(".")
                    else -> append(Pattern.quote(character.toString()))
                }
            }
            append("$")
        }
        else -> return null
    }
    return Pattern.compile(expression, if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
}
