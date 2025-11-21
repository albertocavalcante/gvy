package com.github.albertocavalcante.groovyparser.errors

/**
 * Recovery functions for GroovyParserResult types.
 * Extracted from GroovyParserResult.kt to reduce function count.
 */

/**
 * Recovers from NodeNotFound errors
 */
inline fun <T> GroovyParserResult<T>.recoverFromNodeNotFound(
    recovery: (GroovyParserError.NodeNotFound) -> T,
): GroovyParserResult<T> = recoverCatching { error ->
    when (error) {
        is GroovyParserError.NodeNotFound -> recovery(error)
        else -> throw error
    }
}

/**
 * Recovers from SymbolNotFound errors
 */
inline fun <T> GroovyParserResult<T>.recoverFromSymbolNotFound(
    recovery: (GroovyParserError.SymbolNotFound) -> T,
): GroovyParserResult<T> = recoverCatching { error ->
    when (error) {
        is GroovyParserError.SymbolNotFound -> recovery(error)
        else -> throw error
    }
}
