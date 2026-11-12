// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.fmt.FormatOutcome
import com.github.albertocavalcante.gvy.gls.services.Formatter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CancellationException
import kotlin.math.max

/**
 * Provides formatting code actions using the existing formatter.
 */
class FormattingAction(private val formatter: Formatter) {
    private val logger = KotlinLogging.logger {}

    /**
     * Creates a formatting action if the document needs formatting.
     * Returns null if document is already formatted or formatting fails.
     */
    @Suppress("ReturnCount") // Multiple exit points for clarity in failure cases
    fun createFormattingAction(uriString: String, content: String): CodeAction? {
        logger.debug { "Checking if formatting action is needed for $uriString" }

        val outcome = runCatching { formatter.formatChecked(content) }.getOrElse { failure ->
            if (failure is CancellationException || failure !is Exception) throw failure
            logger.debug(failure) { "Formatter failed, not offering formatting action" }
            return null
        }

        // Code actions are requested often, so a refusal is logged at debug only; formatting the
        // document reports it at WARN.
        if (outcome is FormatOutcome.Refused) {
            logger.debug { "Not offering formatting for $uriString: ${outcome.reason}" }
            return null
        }
        val formattedContent = outcome.text

        // No action if already formatted
        if (formattedContent == content) {
            logger.debug { "Document already formatted, no action needed" }
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
    var line = 0
    var lastLineStart = 0
    this.indices.forEach { index ->
        if (this[index] == '\n') {
            line++
            lastLineStart = index + 1
        }
    }

    var column = length - lastLineStart
    column = max(column, 0)

    return Range(
        Position(0, 0),
        Position(line, column),
    )
}
