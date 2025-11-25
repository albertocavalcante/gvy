package com.github.albertocavalcante.groovylsp.providers.codeaction

import com.github.albertocavalcante.groovylsp.services.Formatter
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.slf4j.LoggerFactory

/**
 * Provides formatting code actions using the existing formatter.
 */
class FormattingAction(private val formatter: Formatter) {
    private val logger = LoggerFactory.getLogger(FormattingAction::class.java)

    /**
     * Creates a formatting action if the document needs formatting.
     * Returns null if document is already formatted or formatting fails.
     */
    @Suppress("ReturnCount") // Multiple exit points for clarity in failure cases
    fun createFormattingAction(uriString: String, content: String): CodeAction? {
        logger.debug("Checking if formatting action is needed for $uriString")

        val formattedContent = try {
            formatter.format(content)
        } catch (e: IllegalStateException) {
            logger.debug("Formatter failed, not offering formatting action", e)
            return null
        } catch (e: IllegalArgumentException) {
            logger.debug("Formatter failed, not offering formatting action", e)
            return null
        }

        // No action if already formatted
        if (formattedContent == content) {
            logger.debug("Document already formatted, no action needed")
            return null
        }

        // Create a full document range edit
        val edit = TextEdit(content.toFullDocumentRange(), formattedContent)

        val workspaceEdit = WorkspaceEdit().apply {
            changes = mapOf(uriString to listOf(edit))
        }

        return CodeAction("Format document").apply {
            kind = CodeActionKind.SourceFixAll
            this.edit = workspaceEdit
        }
    }
}

/**
 * Convert a string to a full document range.
 */
private fun String.toFullDocumentRange(): Range {
    val lines = this.lines()
    return Range(
        Position(0, 0),
        Position(lines.lastIndex, lines.last().length),
    )
}
