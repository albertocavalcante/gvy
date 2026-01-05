package com.github.albertocavalcante.groovyparser.resolution.model

/**
 * Exception thrown when a symbol cannot be resolved.
 *
 * @param name The name of the symbol that could not be resolved
 * @param context Additional context about where the resolution failed
 */
class UnsolvedSymbolException(val symbolName: String, context: String? = null) :
    RuntimeException(
        if (context != null) {
            "Unable to resolve symbol '$symbolName' in $context"
        } else {
            "Unable to resolve symbol '$symbolName'"
        },
    )
