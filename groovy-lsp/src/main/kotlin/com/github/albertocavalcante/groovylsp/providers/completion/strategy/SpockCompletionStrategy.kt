package com.github.albertocavalcante.groovylsp.providers.completion.strategy

import com.github.albertocavalcante.groovylsp.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.groovylsp.dsl.completion.completions
import com.github.albertocavalcante.groovyspock.SpockDetector
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Completion strategy for Spock framework features.
 *
 * Provides completions for:
 * - Spock block labels (given:, when:, then:, expect:, where:, cleanup:, and:)
 *
 * This strategy only activates when:
 * 1. The file is detected as a Spock specification
 * 2. No other completion context is detected (not member access, type parameter, etc.)
 * 3. The cursor is at the start of a line (indent only before cursor)
 * 4. The cursor is not in a comment or string literal
 */
internal class SpockCompletionStrategy : CompletionStrategy {

    override suspend fun complete(context: CompletionStrategyContext): CompletionResult {
        // Only apply in Spock specifications
        // Use heuristic detection since we don't have full ParseResult in context
        val isSpockSpec = SpockDetector.isLikelySpockSpec(
            context.baseContext.uri,
            context.baseContext.content,
        )

        if (!isSpockSpec) {
            return CompletionStrategy.notApplicable("SpockCompletionStrategy")
        }

        // Only suggest block labels when no specific context is detected
        if (context.contextType != null) {
            return CompletionStrategy.notApplicable("SpockCompletionStrategy")
        }

        // Only suggest at line start (indent only before cursor)
        if (!isLineIndentOnlyBeforeCursor(
                context.baseContext.content,
                context.baseContext.line,
                context.baseContext.character,
            )
        ) {
            return CompletionStrategy.notApplicable("SpockCompletionStrategy")
        }

        // Deterministic token-based suppression (don't suggest in comments or strings)
        val offset = offsetAt(
            context.baseContext.content,
            context.baseContext.content.split('\n'),
            context.baseContext.line,
            context.baseContext.character,
        )
        if (context.baseContext.tokenIndex?.isInCommentOrString(offset) == true) {
            return CompletionStrategy.notApplicable("SpockCompletionStrategy")
        }

        val items = completions {
            addSpockBlockLabels()
        }

        return CompletionStrategy.found(items)
    }

    private fun CompletionsBuilder.addSpockBlockLabels() {
        BLOCK_LABELS.forEach { (label, doc) ->
            completion {
                label(label)
                kind(CompletionItemKind.Keyword)
                detail("Spock block label")
                documentation(doc)
                insertText(label)
                // Sort ahead of general keywords
                sortText("0-$label")
            }
        }
    }

    /**
     * Calculate character offset from line/character position.
     */
    private fun offsetAt(content: String, lines: List<String>, line: Int, character: Int): Int {
        var offset = 0
        for (i in 0 until line) {
            offset += lines[i].length + 1 // + '\n'
        }
        return (offset + character).coerceIn(0, content.length)
    }

    /**
     * Check if the line contains only whitespace before the cursor position.
     * This indicates the user is likely starting a new statement, not mid-expression.
     */
    private fun isLineIndentOnlyBeforeCursor(content: String, line: Int, character: Int): Boolean {
        val lines = content.lines()
        if (line !in lines.indices) return false

        val target = lines[line]
        val safeChar = character.coerceIn(0, target.length)
        val prefix = target.substring(0, safeChar)

        // NOTE: Heuristic / tradeoff:
        // We treat "all whitespace before cursor" as a signal that the user is likely starting a Spock block label.
        // This avoids spamming completions mid-expression, but can still misfire in multiline strings/comments.
        // TODO: Use AST to detect LabeledStatement contexts and suppress inside strings/comments when feasible.
        return prefix.all { it == ' ' || it == '\t' }
    }

    companion object {
        /**
         * Spock block labels with their descriptions.
         * These labels are used to structure test methods in Spock specifications.
         */
        val BLOCK_LABELS = listOf(
            "given:" to "Spock setup block",
            "setup:" to "Spock setup block (alias of given)",
            "when:" to "Spock action block",
            "then:" to "Spock assertion block",
            "expect:" to "Spock combined when/then block",
            "where:" to "Spock data-driven block",
            "cleanup:" to "Spock cleanup block",
            "and:" to "Spock block continuation",
        )
    }
}
