package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionKind
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionService
import com.github.albertocavalcante.groovylsp.engine.api.UnifiedDefinition
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.resolution.GroovySymbolResolver
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory

/**
 * Definition service for the Core (JavaParser-style) parser engine.
 *
 * Uses [GroovySymbolResolver] to resolve symbols and find their declarations.
 */
class CoreDefinitionService(private val typeSolver: TypeSolver) : DefinitionService {

    override suspend fun findDefinition(
        node: UnifiedNode?,
        context: ParseUnit,
        position: Position,
    ): List<UnifiedDefinition> {
        // Guard: node must exist
        if (node == null) {
            logger.debug("findDefinition: node is null")
            return emptyList()
        }

        // Guard: node must have a name
        val name = node.name
        if (name == null) {
            logger.debug("findDefinition: node has no name")
            return emptyList()
        }

        // Guard: originalNode must be a core AST Node
        val coreNode = node.originalNode as? Node
        if (coreNode == null) {
            logger.debug("findDefinition: originalNode is not a core AST Node")
            return emptyList()
        }

        // If the node IS a declaration (method, class, field), return it directly
        // No need to "resolve" - the declaration is the definition
        if (isDeclarationNode(coreNode)) {
            val targetRange = node.range ?: extractRangeFromNode(coreNode)
            return listOf(
                UnifiedDefinition(
                    uri = context.uri,
                    range = targetRange,
                    selectionRange = targetRange,
                    kind = DefinitionKind.SOURCE,
                    originSelectionRange = node.range,
                ),
            )
        }

        // For references (variable usages, method calls), use resolver
        return try {
            val resolver = GroovySymbolResolver(typeSolver)
            val symbolRef = resolver.solveSymbol(name, coreNode)

            if (!symbolRef.isSolved) {
                logger.debug("findDefinition: symbol '{}' not resolved", name)
                return emptyList()
            }

            // TODO(#529): Use declaration's range instead of reference's range.
            //   Current limitation: ResolvedValueDeclaration doesn't expose the
            //   declaration's AST node range. Would need to extend the resolution
            //   API in groovyparser-core to expose this. For now, falls back to
            //   the reference location which is still useful for "peek definition".
            val targetRange = node.range ?: Range(Position(0, 0), Position(0, 0))
            listOf(
                UnifiedDefinition(
                    uri = context.uri,
                    range = targetRange,
                    selectionRange = targetRange,
                    kind = DefinitionKind.SOURCE,
                    originSelectionRange = node.range,
                ),
            )
        } catch (e: Exception) {
            logger.debug("findDefinition: error resolving symbol '{}': {}", name, e.message)
            emptyList()
        }
    }

    private fun isDeclarationNode(node: Node): Boolean = when (node) {
        is com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration -> true
        is com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration -> true
        is com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration -> true
        is com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration -> true
        else -> false
    }

    private fun extractRangeFromNode(node: Node): Range {
        val range = node.range ?: return Range(Position(0, 0), Position(0, 0))
        // Convert 1-based to 0-based positions
        return Range(
            Position(range.begin.line - 1, range.begin.column - 1),
            Position(range.end.line - 1, range.end.column - 1),
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CoreDefinitionService::class.java)
    }
}
