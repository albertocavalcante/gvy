package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.slf4j.LoggerFactory

/**
 * Resolves symbols using local AST and symbol table.
 *
 * This strategy handles definitions within the same file:
 * - Local variables
 * - Method parameters
 * - Class fields
 * - Inner classes
 *
 * **Priority: HIGH** - runs after Jenkins vars but before global lookup.
 *
 * **Important:** This strategy ONLY resolves symbols that are defined in the
 * current document. External class references (like org.slf4j.Logger) should
 * fall through to GlobalClassResolutionStrategy or ClasspathResolutionStrategy.
 */
class LocalSymbolResolutionStrategy(private val astVisitor: GroovyAstModel, private val symbolTable: SymbolTable) :
    SymbolResolutionStrategy {

    private val logger = LoggerFactory.getLogger(LocalSymbolResolutionStrategy::class.java)

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        // ImportNode should be resolved by ClasspathResolutionStrategy, not locally
        // Import statements always reference external classes
        if (context.targetNode is ImportNode) {
            logger.debug("ImportNode detected, skipping local resolution")
            return SymbolResolutionStrategy.notFound("ImportNode - defer to classpath resolution", STRATEGY_NAME)
        }

        // Special handling for ClassNode: check if it's defined locally
        if (context.targetNode is ClassNode) {
            return resolveClassNode(context.targetNode, context)
        }

        val definition = try {
            context.targetNode.resolveToDefinition(astVisitor, symbolTable, strict = false)
        } catch (e: StackOverflowError) {
            logger.debug("Stack overflow during local resolution, likely circular reference", e)
            // NOTE: The legacy resolver surfaced a dedicated exception with additional context. In the new pipeline,
            // we keep the resolution non-fatal and return a typed error code for debugging.
            // TODO: Capture the resolution path deterministically (without relying on StackOverflowError) so we can
            // provide actionable circular reference details.
            return SymbolResolutionStrategy.notFound(
                "Error[CIRCULAR_REFERENCE]: stack overflow during local resolution",
                STRATEGY_NAME,
            )
        } catch (e: IllegalArgumentException) {
            logger.debug("Invalid argument during local resolution: ${e.message}", e)
            return SymbolResolutionStrategy.notFound("Invalid argument: ${e.message}", STRATEGY_NAME)
        } catch (e: IllegalStateException) {
            logger.debug("Invalid state during local resolution: ${e.message}", e)
            return SymbolResolutionStrategy.notFound("Invalid state: ${e.message}", STRATEGY_NAME)
        }

        // Filter out non-definition nodes
        val filteredDefinition = when (definition) {
            is ConstantExpression -> null // String literals aren't definitions
            is ClassNode -> {
                // NOTE: Groovy resolution sometimes returns a ClassNode that points at the reference site
                // (e.g. `new Foo()`) rather than the declaration. Prefer the redirected (canonical) node when it
                // looks locally declared, otherwise fall back to our visitor-tracked class declarations.
                // NOTE: ModuleNode-backed lookups are handled by GlobalClassResolutionStrategy; this strategy stays
                // local-only and uses the visitor model for locally-defined classes.
                // TODO: Prefer a deterministic AST-backed link for local classes (or improve visitor parent links)
                // instead of selecting by earliest source position.
                resolveLocalClassDefinition(definition)
            }

            else -> definition
        }

        if (filteredDefinition == null) {
            return SymbolResolutionStrategy.notFound("No local definition found", STRATEGY_NAME)
        }

        // Final check: must have valid position
        if (!hasValidPosition(filteredDefinition)) {
            return SymbolResolutionStrategy.notFound(
                "Definition lacks position info",
                STRATEGY_NAME,
            )
        }

        logger.debug(
            "Resolved local definition: {} at {}:{}",
            filteredDefinition.javaClass.simpleName,
            filteredDefinition.lineNumber,
            filteredDefinition.columnNumber,
        )

        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Source(filteredDefinition, context.documentUri),
        )
    }

    /**
     * Handle ClassNode directly - only resolve if it's defined in the current document.
     */
    private fun resolveClassNode(classNode: ClassNode, context: ResolutionContext): ResolutionResult {
        // Check if this class is defined locally (exists in the AST visitor's class list)
        if (!isLocallyDefinedClass(classNode)) {
            logger.debug("ClassNode {} is an external reference, skipping local resolution", classNode.name)
            return SymbolResolutionStrategy.notFound(
                "ClassNode ${classNode.name} is an external reference",
                STRATEGY_NAME,
            )
        }

        val localClass = resolveLocalClassDefinition(classNode)
        if (localClass == null || !hasValidPosition(localClass)) {
            return SymbolResolutionStrategy.notFound(
                "Class ${classNode.name} not found in local AST",
                STRATEGY_NAME,
            )
        }

        logger.debug(
            "Resolved local class definition: {} at {}:{}",
            localClass.name,
            localClass.lineNumber,
            localClass.columnNumber,
        )
        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Source(localClass, context.documentUri),
        )
    }

    /**
     * Check if a class is defined in the current document's AST.
     *
     * External classes (from JARs, JRT, other files) won't be in getAllClassNodes().
     */
    private fun isLocallyDefinedClass(classNode: ClassNode): Boolean {
        val localClasses = astVisitor.getAllClassNodes()
        // NOTE: ClassNode.name is the fully qualified name (when available), so matching on name avoids
        // collisions with unrelated types that share the same simple name.
        return localClasses.any { it.name == classNode.name }
    }

    private fun resolveLocalClassDefinition(classNode: ClassNode): ClassNode? {
        val redirected = classNode.redirect()
        if (redirected !== classNode && isLocallyDefinedClass(redirected) && hasValidPosition(redirected)) {
            return redirected
        }
        return findLocalClassDeclaration(classNode.name)
    }

    private fun findLocalClassDeclaration(className: String): ClassNode? = astVisitor.getAllClassNodes()
        .asSequence()
        .filter { it.name == className && hasValidPosition(it) }
        .minByOrNull { it.lineNumber }

    private fun hasValidPosition(node: org.codehaus.groovy.ast.ASTNode): Boolean =
        node.lineNumber > 0 && node.columnNumber > 0 && node.lastLineNumber > 0 && node.lastColumnNumber > 0

    companion object {
        private const val STRATEGY_NAME = "LocalSymbol"
    }
}
