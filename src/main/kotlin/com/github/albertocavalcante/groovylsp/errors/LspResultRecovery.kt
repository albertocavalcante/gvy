package com.github.albertocavalcante.groovylsp.errors

/**
 * Recovery functions for LspResult types.
 * Extracted from LspResult.kt to reduce function count.
 */

/**
 * Recovers from NodeNotFound errors
 */
inline fun <T> LspResult<T>.recoverFromNodeNotFound(recovery: (LspError.NodeNotFound) -> T): LspResult<T> =
    recoverCatching { error ->
        when (error) {
            is LspError.NodeNotFound -> recovery(error)
            else -> throw error
        }
    }
