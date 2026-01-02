package com.github.albertocavalcante.groovylsp.indexing

interface IndexWriter : AutoCloseable {
    fun visitDocumentStart(path: String, content: String)
    fun visitDocumentEnd()
    fun visitDefinition(range: Range, symbol: String, isLocal: Boolean, documentation: String? = null)
    fun visitReference(range: Range, symbol: String, isDefinition: Boolean = false)
}

/**
 * Represents a range in a text document.
 * Warning: Uses 1-based indexing (Groovy AST convention).
 * Implementations should convert to 0-based if required (e.g. SCIP/LSIF).
 */
data class Range(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)
