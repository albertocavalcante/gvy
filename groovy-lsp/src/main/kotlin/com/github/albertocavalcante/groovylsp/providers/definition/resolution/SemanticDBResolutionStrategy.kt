package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import org.slf4j.LoggerFactory

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

    private val logger = LoggerFactory.getLogger(SemanticDBResolutionStrategy::class.java)

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        logger.debug("Attempting SemanticDB resolution at {}:{}", context.documentUri, context.position)

        // 1. Get SemanticDocument for current file
        val document = workspaceSymbolIndex.getDocument(context.documentUri)
        if (document == null) {
            logger.debug("No SemanticDocument found for {}", context.documentUri)
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        // 2. Find SymbolOccurrence at cursor position
        val occurrence = document.occurrences.find {
            it.range.contains(context.position.line, context.position.character)
        }
        if (occurrence == null) {
            logger.debug(
                "No occurrence found at {}:{}:{}",
                context.documentUri,
                context.position.line,
                context.position.character,
            )
            return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)
        }

        logger.debug("Found occurrence for symbol: {} at cursor position", occurrence.symbol)

        // 3. Use WorkspaceSymbolIndex to find definition
        val location = workspaceSymbolIndex.findDefinition(occurrence.symbol)
        if (location == null) {
            logger.debug("Symbol {} not found in workspace index", occurrence.symbol)
            return SymbolResolutionStrategy.notFound("Symbol not found in workspace index", STRATEGY_NAME)
        }

        logger.debug("Resolved symbol {} to {}", occurrence.symbol, location.uri)

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
    }
}
