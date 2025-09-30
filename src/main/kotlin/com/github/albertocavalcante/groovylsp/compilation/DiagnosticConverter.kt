package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.dsl.RangeBuilder
import com.github.albertocavalcante.groovylsp.dsl.diagnostic
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Converts Groovy compilation errors to LSP diagnostics.
 */
@Suppress("TooGenericExceptionCaught") // Diagnostic conversion handles all compiler error types
object DiagnosticConverter {

    private val logger = LoggerFactory.getLogger(DiagnosticConverter::class.java)

    /**
     * Converts an ErrorCollector from Groovy compilation to a list of LSP diagnostics.
     *
     * @deprecated Use convertErrorCollectorWithAttribution for workspace compilation
     */
    fun convertErrorCollector(errorCollector: ErrorCollector): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        // Convert errors
        errorCollector.errors?.forEach { message ->
            when (message) {
                is SyntaxErrorMessage -> {
                    val diagnostic = convertSyntaxError(message.cause)
                    diagnostics.add(diagnostic)
                }
                else -> {
                    // Handle other message types generically
                    val diagnostic = convertGenericMessage(message)
                    diagnostics.add(diagnostic)
                }
            }
        }

        // Convert warnings
        errorCollector.warnings?.forEach { warning ->
            val diagnostic = convertGenericMessage(warning, DiagnosticSeverity.Warning)
            diagnostics.add(diagnostic)
        }

        return diagnostics
    }

    /**
     * Converts an ErrorCollector from Groovy compilation to diagnostics mapped by source URI.
     * This method properly attributes each error to its originating source file.
     */
    fun convertErrorCollectorWithAttribution(
        errorCollector: ErrorCollector,
        sourceUnits: Map<URI, SourceUnit>,
    ): Map<URI, List<Diagnostic>> {
        val diagnosticsByUri = mutableMapOf<URI, MutableList<Diagnostic>>()

        // Convert errors with source attribution
        errorCollector.errors?.forEach { message ->
            when (message) {
                is SyntaxErrorMessage -> {
                    val sourceUnit = extractSourceUnit(message)
                    val uri = findUriForSourceUnit(sourceUnit, sourceUnits)

                    if (uri != null) {
                        val diagnostic = convertSyntaxError(message.cause)
                        diagnosticsByUri.getOrPut(uri) { mutableListOf() }.add(diagnostic)
                    }
                }
                else -> {
                    // For generic messages without clear source attribution,
                    // we'll need to handle them differently based on context
                    val diagnostic = convertGenericMessage(message)

                    // For now, try to extract source info if available
                    val sourceUnit = extractSourceUnitFromGenericMessage(message)
                    val uri = sourceUnit?.let { findUriForSourceUnit(it, sourceUnits) }

                    if (uri != null) {
                        diagnosticsByUri.getOrPut(uri) { mutableListOf() }.add(diagnostic)
                    }
                    // If we can't attribute it, we'll skip it rather than pollute all files
                }
            }
        }

        // Convert warnings with source attribution
        errorCollector.warnings?.forEach { warning ->
            val diagnostic = convertGenericMessage(warning, DiagnosticSeverity.Warning)

            val sourceUnit = extractSourceUnitFromGenericMessage(warning)
            val uri = sourceUnit?.let { findUriForSourceUnit(it, sourceUnits) }

            if (uri != null) {
                diagnosticsByUri.getOrPut(uri) { mutableListOf() }.add(diagnostic)
            }
        }

        return diagnosticsByUri.mapValues { it.value.toList() }
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

    /**
     * Extracts the SourceUnit from a SyntaxErrorMessage using reflection.
     * SyntaxErrorMessage has a protected 'source' field we need to access.
     */
    private fun extractSourceUnit(syntaxErrorMessage: SyntaxErrorMessage): SourceUnit? = try {
        // Use reflection to access the protected 'source' field
        val field = syntaxErrorMessage.javaClass.getDeclaredField("source")
        field.isAccessible = true
        field.get(syntaxErrorMessage) as? SourceUnit
    } catch (e: Exception) {
        logger.debug("Failed to extract source unit from syntax error message", e)
        null
    }

    /**
     * Attempts to extract SourceUnit from a generic Message.
     * This may not always be possible, depending on the message type.
     */
    private fun extractSourceUnitFromGenericMessage(message: Message): SourceUnit? = try {
        // Try to access a 'source' field if it exists
        val field = message.javaClass.getDeclaredField("source")
        field.isAccessible = true
        field.get(message) as? SourceUnit
    } catch (e: Exception) {
        logger.debug("Failed to extract source unit from generic message", e)
        null
    }

    /**
     * Finds the URI that corresponds to a given SourceUnit by comparing with our mapping.
     */
    private fun findUriForSourceUnit(sourceUnit: SourceUnit?, sourceUnits: Map<URI, SourceUnit>): URI? {
        if (sourceUnit == null) return null

        return sourceUnits.entries.find { (_, unit) ->
            // Compare by object identity first
            unit === sourceUnit ||
                // Fall back to name comparison if needed
                unit.name == sourceUnit.name
        }?.key
    }
}
