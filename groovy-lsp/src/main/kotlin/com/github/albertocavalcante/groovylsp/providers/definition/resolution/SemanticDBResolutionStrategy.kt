package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ImportNode

/**
 * Resolves symbols using SemanticDB and WorkspaceSymbolIndex for full cross-file resolution.
 *
 * This strategy handles cross-file resolution for all symbol types:
 * - Methods (cross-file method calls)
 * - Fields (cross-file field access)
 * - Properties (cross-file property access)
 * - Constructors (cross-file constructor calls)
 * - Inherited members (methods/fields from parent classes)
 *
 * **Priority: MEDIUM** - runs after LocalSymbol (same-file) but before GlobalClass (class-only).
 *
 * Uses the SemanticDB to find occurrences at the cursor position, then resolves them
 * to definitions using the WorkspaceSymbolIndex.
 *
 * @property workspaceSymbolIndex Index for workspace-wide symbol lookup (provides access to SemanticDB)
 */
class SemanticDBResolutionStrategy(private val workspaceSymbolIndex: WorkspaceSymbolIndex) : SymbolResolutionStrategy {

    private val logger = KotlinLogging.logger {}

    @Suppress("ReturnCount") // Multiple validation checks require early returns
    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        logger.debug { "Attempting SemanticDB resolution at ${context.documentUri}:${context.position}" }

        if (context.targetNode is ImportNode) {
            logger.debug { "Skipping SemanticDB resolution for ImportNode" }
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        // 1. Get SemanticDocument for current file
        val document = workspaceSymbolIndex.getDocument(context.documentUri)
        if (document == null) {
            logger.debug { "No SemanticDocument found for ${context.documentUri}" }
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        // 2. Find closest/smallest SymbolOccurrence at cursor position
        // Multiple occurrences may overlap (e.g. Class contains Method contains Expression)
        val line = context.position.line
        val char = context.position.character

        val occurrence = document.occurrences
            .filter { it.range.contains(line, char) && it.range.startLine == it.range.endLine }
            .minByOrNull {
                // Calculate total characters roughly or use lexicographical comparison
                val lineDiff = it.range.endLine - it.range.startLine
                if (lineDiff == 0) {
                    it.range.endColumn - it.range.startColumn
                } else {
                    // Prioritize line count for multi-lines, add large constant to differentiate from single line
                    lineDiff * MULTI_LINE_PRIORITY_WEIGHT + (it.range.endColumn)
                }
            }
        if (occurrence == null) {
            logger.debug {
                "No occurrence found at ${context.documentUri}:${context.position.line}:${context.position.character}"
            }
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        logger.debug { "Found occurrence for symbol: ${occurrence.symbol} at cursor position" }

        // 3. Use WorkspaceSymbolIndex to find definition
        val location = workspaceSymbolIndex.findDefinition(occurrence.symbol)
        if (location == null) {
            logger.debug { "Symbol ${occurrence.symbol} not found in workspace index" }
            return SymbolResolutionStrategy.notFound("Symbol not found in workspace index", STRATEGY_NAME)
        }

        logger.debug { "Resolved symbol ${occurrence.symbol} to ${location.uri}" }

        // 4. Get symbol info to extract the name
        val symbolInfo = workspaceSymbolIndex.findSymbol(occurrence.symbol)
        val symbolName = symbolInfo?.name ?: extractNameFromSymbolId(occurrence.symbol)

        // 5. Return DefinitionResult.Binary (we don't have access to AST nodes in this strategy)
        // The LSP will handle loading the source when the user navigates to the definition
        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Binary(
                uri = java.net.URI(location.uri),
                name = symbolName,
                range = location.range,
            ),
        )
    }

    /**
     * Extract the simple name from a SemanticDB symbol ID.
     *
     * Examples:
     * - "com/example/MyClass#myMethod()." -> "myMethod"
     * - "com/example/MyClass#myField." -> "myField"
     * - "com/example/MyClass#`<init>`()." -> "<init>"
     */
    private fun extractNameFromSymbolId(symbolId: String): String {
        // Find the last # which separates class from member
        val hashIndex = symbolId.lastIndexOf('#')
        if (hashIndex < 0) {
            return symbolId
        }

        // Extract the member part (e.g., "myMethod()." or "myField.")
        val memberPart = symbolId.substring(hashIndex + 1)

        // Handle backtick-quoted names like `<init>`
        if (memberPart.startsWith('`')) {
            val endBacktick = memberPart.indexOf('`', 1)
            if (endBacktick > 0) {
                return memberPart.substring(1, endBacktick)
            }
        }

        // Extract name before parentheses (for methods) or dot (for fields/properties)
        val parenIndex = memberPart.indexOf('(')
        val dotIndex = memberPart.indexOf('.')

        return when {
            parenIndex >= 0 -> memberPart.substring(0, parenIndex)
            dotIndex >= 0 -> memberPart.substring(0, dotIndex)
            else -> memberPart
        }
    }

    companion object {
        private const val STRATEGY_NAME = "SemanticDB"

        /**
         * Priority weight for multi-line occurrences.
         *
         * NOTE: Value chosen to ensure any multi-line range is deprioritized
         * compared to single-line ranges (max ~200 chars/line). 10000 >> 200.
         */
        private const val MULTI_LINE_PRIORITY_WEIGHT = 10000
    }
}
