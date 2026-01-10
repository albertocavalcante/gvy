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
            val classNode = loadClassNodeFromAst(uri, matchingSymbol.fullyQualifiedName)
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

    @Suppress("ReturnCount") // Multiple validation checks require early returns
    private suspend fun loadClassNodeFromAst(uri: java.net.URI, className: String): ClassNode? {
        // Ensure the file is compiled (handles case where file is in index but not yet compiled)
        val compilationResult = compilationService.ensureCompiled(uri)
        if (compilationResult == null) {
            logger.warn(
                "Cannot load AST for {} - file not compiled and no active compilation found. " +
                    "This may indicate the file was deleted or is not accessible.",
                uri,
            )
            return null
        }

        val ast = compilationService.getAst(uri)
        if (ast == null) {
            logger.warn(
                "AST is null for {} after ensureCompiled returned successfully. " +
                    "This indicates a cache inconsistency.",
                uri,
            )
            return null
        }

        val moduleNode = ast as? ModuleNode
        if (moduleNode == null) {
            logger.warn(
                "AST for {} is not a ModuleNode (actual type: {}). " +
                    "This parser may not be supported for cross-file resolution.",
                uri,
                ast.javaClass.simpleName,
            )
            return null
        }

        val classNode = moduleNode.classes.find { it.name == className }
        if (classNode == null) {
            logger.debug(
                "ClassNode {} not found in ModuleNode.classes for {}. Available classes: [{}]",
                className,
                uri,
                moduleNode.classes.joinToString { it.name },
            )
        }

        return classNode
    }

    companion object {
        private const val STRATEGY_NAME = "GlobalClass"
    }
}
