package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.groovyparser.ast.containsPosition
import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.completions
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockDataVariables
import com.github.albertocavalcante.gvy.spock.SpockDetector
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Completion strategy for Spock framework features.
 *
 * Provides completions for:
 * - Spock block labels at statement starts
 * - Data variables from the owning feature, including declarations later in the where block
 *
 * This strategy only activates when:
 * 1. The file is detected as a Spock specification
 * 2. No other completion context is detected (not member access, type parameter, etc.)
 * 3. The cursor is at a statement start or a data-variable prefix
 * 4. The cursor is not in a comment or string literal
 */
internal class SpockCompletionStrategy : CompletionStrategy {

    override suspend fun complete(context: CompletionStrategyContext): CompletionResult {
        val base = context.baseContext
        val line = base.content.lines().getOrNull(base.line)
        if (line == null || context.contextType != null) {
            return CompletionStrategy.notApplicable("SpockCompletionStrategy")
        }
        val offset = offsetAt(base.content, base.content.split('\n'), base.line, base.character)
        val source = base.moduleNode?.let { SpockSourceContext.from(it, isPartial = false) }
        val isSpockSpec = isSpecificationAt(context, source)
        if (!isSpockSpec || base.tokenIndex?.isInCommentOrString(offset) == true) {
            return CompletionStrategy.notApplicable("SpockCompletionStrategy")
        }
        val dataItems = source?.let { dataCompletions(context, line, it) }.orEmpty()
        val labels = if (isLineIndentOnlyBeforeCursor(base.content, base.line, base.character)) {
            completions { addSpockBlockLabels() }
        } else {
            emptyList()
        }
        val items = labels + dataItems
        return if (items.isEmpty()) {
            CompletionStrategy.notApplicable("SpockCompletionStrategy")
        } else {
            CompletionStrategy.found(items)
        }
    }

    /**
     * Whether the cursor sits in a Spock specification (#974). A confident AST verdict for the declared
     * owner at the cursor decides; anonymous bodies belong to their declaring spec. An UNCERTAIN
     * verdict or a missing AST keeps the previous text heuristic. A cursor in script code also falls
     * back, but only because Groovy gives the synthetic script class no source position, so
     * `declaredOwnerAt` finds no owner there; it is not an explicit script check.
     */
    private fun isSpecificationAt(context: CompletionStrategyContext, source: SpockSourceContext.Available?): Boolean {
        val base = context.baseContext
        val owner = source?.declaredOwnerAt(base.line, base.character)
        return when (owner?.status) {
            SpecificationStatus.SPECIFICATION -> true
            SpecificationStatus.NOT_SPECIFICATION -> false
            SpecificationStatus.UNCERTAIN, null -> SpockDetector.isLikelySpockSpec(base.uri, base.content)
        }
    }

    private fun dataCompletions(
        context: CompletionStrategyContext,
        line: String,
        source: SpockSourceContext.Available,
    ): List<CompletionItem> {
        val base = context.baseContext
        val module = base.moduleNode ?: return emptyList()
        val prefix = line.take(base.character.coerceIn(0, line.length))
            .takeLastWhile { Character.isJavaIdentifierPart(it) }
        val names = source.classes.filter { owner -> !owner.classNode.isScript && owner.isSpecification(module) }
            .map { it.classNode }
            .flatMap { it.methods }
            .filter { it.containsPosition(base.line, base.character) }
            .flatMap { SpockDataVariables.extract(it).keys }
            .distinct()
            .filter { it.startsWith(prefix) }
        return completions {
            names.forEach { name ->
                completion {
                    label(name)
                    kind(CompletionItemKind.Variable)
                    detail("Spock data variable")
                    insertText(name)
                }
            }
        }
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
        // Token suppression above handles comments and strings; this check only gates block labels.
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
