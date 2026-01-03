package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.api.ParseResult
import org.slf4j.LoggerFactory

/**
 * Maps technical ParseResult into consumer-facing CompilationResult.
 * Handles diagnostic conversion, AST extraction, and result status determination.
 */
class CompilationResultMapper {
    private val logger = LoggerFactory.getLogger(CompilationResultMapper::class.java)

    /**
     * Maps a ParseResult into a CompilationResult.
     */
    fun map(parseResult: ParseResult, content: String): CompilationResult {
        val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
        val ast = parseResult.ast

        return if (ast != null) {
            val isSuccess = parseResult.isSuccessful
            CompilationResult(isSuccess, ast, diagnostics, content)
        } else {
            logger.debug("Parse result has no AST, returning failure result")
            CompilationResult.failure(diagnostics, content)
        }
    }

    /**
     * Maps a cached ParseResult into a CompilationResult.
     */
    fun mapFromCache(parseResult: ParseResult, content: String): CompilationResult? {
        val ast = parseResult.ast ?: return null
        val diagnostics = parseResult.diagnostics.map { it.toLspDiagnostic() }
        return CompilationResult(parseResult.isSuccessful, ast, diagnostics, content)
    }
}
