// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Maps technical ParseResult into consumer-facing CompilationResult.
 * Handles diagnostic conversion, AST extraction, and result status determination.
 */
class CompilationResultMapper {
    private val logger = KotlinLogging.logger {}

    /**
     * Maps a ParseResult into a CompilationResult.
     */
    fun map(parseResult: ParseResult, content: String): CompilationResult {
        val diagnostics = diagnosticsOf(parseResult)
        val ast = parseResult.ast

        return if (ast != null) {
            val isSuccess = parseResult.isSuccessful
            CompilationResult(
                isSuccess,
                ast,
                diagnostics,
                content,
                parseCompleted = true,
                sourceAnnotations = parseResult.sourceAnnotations,
                astModel = parseResult.astModel,
                outcome = parseResult.outcome,
            )
        } else {
            logger.debug { "Parse result has no AST, returning failure result" }
            CompilationResult(
                false,
                null,
                diagnostics,
                content,
                parseCompleted = true,
                sourceAnnotations = parseResult.sourceAnnotations,
                astModel = parseResult.astModel,
                outcome = parseResult.outcome,
            )
        }
    }

    /**
     * Maps a cached ParseResult into a CompilationResult.
     */
    fun mapFromCache(parseResult: ParseResult, content: String): CompilationResult? {
        val ast = parseResult.ast ?: return null
        val diagnostics = diagnosticsOf(parseResult)
        return CompilationResult(
            parseResult.isSuccessful,
            ast,
            diagnostics,
            content,
            parseCompleted = true,
            sourceAnnotations = parseResult.sourceAnnotations,
            astModel = parseResult.astModel,
            outcome = parseResult.outcome,
        )
    }

    /** The parser's diagnostics, plus the notice that types are unresolved when the parse degraded. */
    private fun diagnosticsOf(parseResult: ParseResult) = parseResult.diagnostics.map { it.toLspDiagnostic() } +
        listOfNotNull(parseResult.outcome.toDegradationDiagnostic())
}
