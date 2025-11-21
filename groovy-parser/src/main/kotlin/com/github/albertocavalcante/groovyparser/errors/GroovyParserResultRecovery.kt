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
