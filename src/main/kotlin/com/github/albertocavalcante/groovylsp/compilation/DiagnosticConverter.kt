package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.dsl.RangeBuilder
import com.github.albertocavalcante.groovylsp.dsl.diagnostic
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/**
 * Converts Groovy compilation errors to LSP diagnostics.
 */
object DiagnosticConverter {

    private val logger = org.slf4j.LoggerFactory.getLogger(DiagnosticConverter::class.java)

    /**
     * Converts an ErrorCollector from Groovy compilation to a list of LSP diagnostics.
     */
    fun convertErrorCollector(
        errorCollector: ErrorCollector,
        allowedLocators: Set<String> = emptySet(),
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        // Convert errors
        errorCollector.errors?.forEach { message ->
            when (message) {
                is SyntaxErrorMessage -> {
                    val syntaxException = message.cause
                    if (allowedLocators.isNotEmpty() &&
                        !matchesLocator(syntaxException.sourceLocator, allowedLocators)
                    ) {
                        return@forEach
                    }
                    logger.debug(
                        "Diagnostic from sourceLocator='{}', message='{}', range=({}, {})-({}, {})",
                        syntaxException.sourceLocator,
                        syntaxException.originalMessage,
                        syntaxException.line,
                        syntaxException.startColumn,
                        syntaxException.line,
                        syntaxException.endColumn,
                    )
                    val diagnostic = convertSyntaxError(syntaxException)
                    diagnostics.add(diagnostic)
                }
                else -> {
                    if (allowedLocators.isNotEmpty()) {
                        return@forEach
                    }
                    // Handle other message types generically
                    val diagnostic = convertGenericMessage(message)
                    diagnostics.add(diagnostic)
                }
            }
        }

        // Convert warnings
        errorCollector.warnings?.forEach { warning ->
            if (allowedLocators.isNotEmpty()) {
                return@forEach
            }
            val diagnostic = convertGenericMessage(warning, DiagnosticSeverity.Warning)
            diagnostics.add(diagnostic)
        }

        return diagnostics
    }

    /**
     * Converts a Groovy SyntaxException to an LSP diagnostic using DSL.
     */
    private fun convertSyntaxError(syntaxException: SyntaxException): Diagnostic = diagnostic {
        // Convert from 1-based to 0-based indexing
        val lspLine = maxOf(0, syntaxException.line - 1)
        val lspStartColumn = maxOf(0, syntaxException.startColumn - 1)
        val lspEndColumn = maxOf(lspStartColumn, syntaxException.endColumn - 1)

        range(RangeBuilder.range(lspLine, lspStartColumn, lspEndColumn))
        error(syntaxException.message ?: "Syntax error")
        source("groovy-lsp")
        code("syntax-error")
    }

    /**
     * Converts a generic Groovy message to an LSP diagnostic using DSL.
     */
    private fun convertGenericMessage(
        message: Message,
        severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    ): Diagnostic = diagnostic {
        // For generic messages, default to point range at (0,0)
        range(RangeBuilder.at(0, 0))
        if (severity == DiagnosticSeverity.Warning) {
            warning(message.toString())
        } else {
            error(message.toString())
        }
        source("groovy-lsp")
        code("compilation-error")
    }

    private fun matchesLocator(sourceLocator: String?, allowedLocators: Set<String>): Boolean {
        if (sourceLocator == null) {
            return false
        }
        return allowedLocators.any { candidate ->
            candidate.equals(sourceLocator, ignoreCase = false)
        }
    }
}
