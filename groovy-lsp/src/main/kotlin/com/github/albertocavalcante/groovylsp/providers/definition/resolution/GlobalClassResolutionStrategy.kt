package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.slf4j.LoggerFactory

/**
 * Resolves class references across workspace files using symbol index.
 *
 * This strategy handles cross-file navigation within the project:
 * - Class references from other Groovy files
 * - Import statements pointing to project classes
 *
 * **Priority: MEDIUM** - runs after local symbol resolution.
 */
class GlobalClassResolutionStrategy(private val compilationService: GroovyCompilationService) :
    SymbolResolutionStrategy {

    private val logger = LoggerFactory.getLogger(GlobalClassResolutionStrategy::class.java)

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val className = getClassName(context.targetNode)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        logger.debug("Attempting global lookup for class: {}", className)

        // Extract simple name from potentially fully-qualified name for matching
        val simpleClassName = className.substringAfterLast('.')

        // Search all symbol indices across workspace files
        for ((uri, index) in compilationService.getAllSymbolStorages()) {
            val symbols = index.getSymbols(uri).filterIsInstance<Symbol.Class>()
            // Match against both simple name and fully qualified name
            val matchingSymbol = symbols.find {
                it.name == simpleClassName || it.fullyQualifiedName == className
            } ?: continue

            // Found it in the index, now load the actual ClassNode from AST
            val classNode = loadClassNodeFromAst(uri, matchingSymbol.name)
            if (classNode != null) {
                logger.debug("Found global class definition for {} at {}", className, uri)
                return SymbolResolutionStrategy.found(
                    DefinitionResolver.DefinitionResult.Source(classNode, uri),
                )
            } else {
                logger.warn("Symbol found in index but ClassNode not found in AST for {} at {}", className, uri)
            }
        }

        return SymbolResolutionStrategy.notFound(
            "Class $className not found in workspace symbol index",
            STRATEGY_NAME,
        )
    }

    private fun loadClassNodeFromAst(uri: java.net.URI, className: String): ClassNode? {
        val ast = compilationService.getAst(uri) ?: return null
        return (ast as? ModuleNode)?.classes?.find { it.name == className }
    }

    companion object {
        private const val STRATEGY_NAME = "GlobalClass"
    }
}
