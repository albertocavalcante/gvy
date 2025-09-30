package com.github.albertocavalcante.groovylsp.providers.formatting

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Provides document formatting capabilities for Groovy files.
 * Integrates with the LSP to format entire documents or specific ranges.
 */
class FormattingProvider(
    private val compilationService: GroovyCompilationService
) {
    private val logger = LoggerFactory.getLogger(FormattingProvider::class.java)
    private val basicFormatter = BasicIndentationFormatter()

    /**
     * Formats the entire document.
     *
     * @param uri The document URI
     * @param options Formatting options from the client
     * @return List of text edits to apply
     */
    fun formatDocument(uri: String, options: FormattingOptions): List<TextEdit> {
        logger.debug("Formatting document: $uri")

        try {
            val content = getDocumentContent(uri)
            return formatContent(content, options)
        } catch (e: Exception) {
            logger.error("Error formatting document $uri", e)
            return emptyList()
        }
    }

    /**
     * Formats a specific range within the document.
     *
     * @param uri The document URI
     * @param range The range to format
     * @param options Formatting options from the client
     * @return List of text edits to apply
     */
    fun formatRange(uri: String, range: Range, options: FormattingOptions): List<TextEdit> {
        logger.debug("Formatting range in document: $uri, range: $range")

        try {
            val content = getDocumentContent(uri)
            val lines = content.lines()

            // Extract the range content
            val startLine = range.start.line
            val endLine = minOf(range.end.line, lines.size - 1)

            if (startLine > endLine || startLine >= lines.size) {
                logger.warn("Invalid range for formatting: $range in document with ${lines.size} lines")
                return emptyList()
            }

            // Get the content within the range
            val rangeContent = lines.subList(startLine, endLine + 1).joinToString("\n")

            // Format the range content
            val formatted = basicFormatter.format(rangeContent, options)

            // Create a text edit for the range
            if (formatted != rangeContent) {
                val rangeEnd = Position(
                    endLine,
                    if (endLine < lines.size) lines[endLine].length else 0
                )

                return listOf(
                    TextEdit(
                        Range(range.start, rangeEnd),
                        formatted
                    )
                )
            }

            return emptyList()
        } catch (e: Exception) {
            logger.error("Error formatting range in document $uri", e)
            return emptyList()
        }
    }

    /**
     * Formats content and returns the necessary text edits.
     */
    private fun formatContent(content: String, options: FormattingOptions): List<TextEdit> {
        if (content.isBlank()) {
            return emptyList()
        }

        // Apply formatting
        var formatted = basicFormatter.format(content, options)

        // Remove trailing whitespace
        formatted = basicFormatter.removeTrailingWhitespace(formatted)

        // Ensure trailing newline
        formatted = basicFormatter.ensureTrailingNewline(formatted)

        // If content changed, return a single edit replacing the entire document
        if (formatted != content) {
            val lines = content.lines()
            val lastLine = maxOf(0, lines.size - 1)
            val lastChar = if (lines.isEmpty()) 0 else lines.last().length

            return listOf(
                TextEdit(
                    Range(
                        Position(0, 0),
                        Position(lastLine, lastChar)
                    ),
                    formatted
                )
            )
        }

        return emptyList()
    }

    /**
     * Gets the document content, either from the compilation cache or by reading the file.
     */
    private fun getDocumentContent(uri: String): String {
        return try {
            // Try to get content from compilation cache first
            val parsedUri = URI.create(uri)

            // If we have a cached AST, we can't get the original content from it,
            // so we read from file. In a full implementation, we'd cache the content too.
            val path = Paths.get(parsedUri)
            Files.readString(path)
        } catch (e: Exception) {
            logger.error("Failed to read document content for $uri", e)
            throw IllegalStateException("Cannot read document content for formatting", e)
        }
    }
}