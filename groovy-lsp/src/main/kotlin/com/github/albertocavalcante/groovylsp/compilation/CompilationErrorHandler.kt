package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.dsl.RangeBuilder
import com.github.albertocavalcante.groovylsp.dsl.diagnostic
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.errors.syntaxError
import org.codehaus.groovy.control.CompilationFailedException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI

/**
 * Error handler component for compilation service.
 * Converts various exceptions into appropriate diagnostic results.
 */
class CompilationErrorHandler {
    private val logger = LoggerFactory.getLogger(CompilationErrorHandler::class.java)

    fun handleException(e: Exception, uri: URI): CompilationResult = when (e) {
        is CompilationFailedException -> handleCompilationFailed(e, uri)
        is GroovyLspException -> handleGroovyParserError(e)
        is IllegalArgumentException -> handleInvalidArgs(e)
        is IllegalStateException -> handleInvalidState(e)
        is IOException -> handleIOError(e)
        else -> handleUnexpected(e)
    }

    private fun handleCompilationFailed(e: CompilationFailedException, uri: URI): CompilationResult {
        val diagnostic = if (e.message?.contains("Syntax error", ignoreCase = true) == true) {
            createSyntaxErrorDiagnostic(e, uri)
        } else {
            createGeneralErrorDiagnostic(e)
        }
        return CompilationResult.failure(listOf(diagnostic))
    }

    private fun handleGroovyParserError(e: GroovyLspException): CompilationResult {
        val diagnostic = createDiagnostic("LSP error: ${e.message}", DiagnosticSeverity.Error)
        return CompilationResult.failure(listOf(diagnostic))
    }

    private fun handleInvalidArgs(e: IllegalArgumentException): CompilationResult {
        val diagnostic = createDiagnostic("Invalid arguments: ${e.message}", DiagnosticSeverity.Error)
        return CompilationResult.failure(listOf(diagnostic))
    }

    private fun handleInvalidState(e: IllegalStateException): CompilationResult {
        val diagnostic = createDiagnostic("Invalid state: ${e.message}", DiagnosticSeverity.Error)
        return CompilationResult.failure(listOf(diagnostic))
    }

    private fun handleIOError(e: IOException): CompilationResult {
        val diagnostic = createDiagnostic("I/O error: ${e.message}", DiagnosticSeverity.Error)
        return CompilationResult.failure(listOf(diagnostic))
    }

    private fun handleUnexpected(e: Exception): CompilationResult {
        logger.error("Unexpected compilation error", e)
        val diagnostic = createDiagnostic("Compilation error: ${e.message}", DiagnosticSeverity.Error)
        return CompilationResult.failure(listOf(diagnostic))
    }

    private fun createSyntaxErrorDiagnostic(e: CompilationFailedException, uri: URI): Diagnostic {
        val lineColumn = extractLineColumnFromMessage(e.message)
        val specificException = uri.syntaxError(
            lineColumn?.first ?: 0,
            lineColumn?.second ?: 0,
            e.message ?: "Unknown syntax error",
            e,
        )
        return createDiagnostic(
            "${specificException.javaClass.simpleName}: ${specificException.message}",
            DiagnosticSeverity.Error,
            lineColumn,
        )
    }

    private fun createGeneralErrorDiagnostic(e: CompilationFailedException): Diagnostic =
        createDiagnostic("Compilation failed: ${e.message ?: "Unknown compilation error"}", DiagnosticSeverity.Error)

    fun createDiagnostic(
        message: String,
        severity: DiagnosticSeverity,
        lineColumn: Pair<Int, Int>? = null,
    ): Diagnostic = diagnostic {
        if (lineColumn != null) {
            val (line, column) = lineColumn
            // LSP uses 0-based indexing, Groovy uses 1-based
            val startLine = (line - 1).coerceAtLeast(0)
            val startChar = (column - 1).coerceAtLeast(0)
            range(RangeBuilder.at(startLine, startChar))
        } else {
            range(RangeBuilder.at(0, 0))
        }
        when (severity) {
            DiagnosticSeverity.Error -> error(message)
            DiagnosticSeverity.Warning -> warning(message)
            DiagnosticSeverity.Information -> info(message)
            DiagnosticSeverity.Hint -> info(message) // fallback to info for hint
        }
        source("gls")
        code("compilation-error")
    }

    /**
     * Extract line and column information from Groovy compiler error messages.
     * Groovy error messages often contain position information like "@ line 5, column 10"
     */
    private fun extractLineColumnFromMessage(message: String?): Pair<Int, Int>? {
        if (message == null) return null

        // Look for patterns like "@ line 5, column 10" or "line: 5, column: 10"
        val lineColumnRegex = """(?:@\s*)?line[:\s]*(\d+)(?:[,\s]*column[:\s]*(\d+))?""".toRegex(
            RegexOption.IGNORE_CASE,
        )
        val match = lineColumnRegex.find(message)

        return match?.let { matchResult ->
            val line = matchResult.groups[1]?.value?.toIntOrNull() ?: 0
            val column = matchResult.groups[2]?.value?.toIntOrNull() ?: 0
            line to column
        }
    }
}
