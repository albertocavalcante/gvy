package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionKind
import com.github.albertocavalcante.groovylsp.engine.api.DefinitionService
import com.github.albertocavalcante.groovylsp.engine.api.UnifiedDefinition
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
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

    // Reuse resolver instance for efficiency (created once per service instance)
    private val resolver by lazy { GroovySymbolResolver(typeSolver) }

    override suspend fun findDefinition(
        node: UnifiedNode?,
        context: ParseUnit,
        position: Position,
    ): List<UnifiedDefinition> {
        if (node == null || node.name == null || node.originalNode !is Node) {
            logger.debug("findDefinition: invalid node or missing name")
            return emptyList()
        }

        val name = node.name
        val coreNode = node.originalNode

        // If the node IS a declaration (method, class, field), return it directly
        if (isDeclarationNode(coreNode)) {
            val targetRange = node.range ?: extractRangeFromNode(coreNode) ?: return emptyList()
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
            val symbolRef = resolver.solveSymbol(name, coreNode)

            if (!symbolRef.isSolved) {
                logger.debug("findDefinition: symbol '{}' not resolved", name)
                return emptyList()
            }

            // Use declaration's AST node to get its source range
            val declaration = symbolRef.getDeclaration()
            val declNode = declaration.declarationNode
            if (declNode == null) {
                logger.debug("findDefinition: declaration has no AST node for '{}'", name)
                return emptyList()
            }

            val targetRange = extractRangeFromNode(declNode)
            if (targetRange == null) {
                logger.debug("findDefinition: declaration node has no range for '{}'", name)
                return emptyList()
            }

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
        is ClassDeclaration, is MethodDeclaration, is FieldDeclaration, is ConstructorDeclaration -> true
        else -> false
    }

    private fun extractRangeFromNode(node: Node): Range? {
        val range = node.range ?: return null
        // Convert 1-based to 0-based positions
        return Range(
            Position(range.begin.line - 1, range.begin.column - 1),
            Position(range.end.line - 1, range.end.column),
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CoreDefinitionService::class.java)
    }
}
