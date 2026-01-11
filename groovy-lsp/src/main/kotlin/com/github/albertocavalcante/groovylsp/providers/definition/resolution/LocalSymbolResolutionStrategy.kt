@file:Suppress("ReturnCount") // Early returns are clearer for multi-stage resolution logic

package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.resolveToDefinition
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import java.net.URI

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

    private val logger = KotlinLogging.logger {}

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        logger.info { "=== LocalSymbolResolutionStrategy.resolve ===" }
        logger.info { "Target node type: ${context.targetNode.javaClass.simpleName}" }
        logger.info { "Target node position: ${context.targetNode.lineNumber}:${context.targetNode.columnNumber}" }
        logger.info { "Document URI: ${context.documentUri}" }

        // ImportNode should be resolved by ClasspathResolutionStrategy, not locally
        // Import statements always reference external classes
        if (context.targetNode is ImportNode) {
            logger.debug { "ImportNode detected, skipping local resolution" }
            return SymbolResolutionStrategy.notFound("ImportNode - defer to classpath resolution", STRATEGY_NAME)
        }

        // ConstantExpression (literals) should not resolve to symbols locally
        if (context.targetNode is ConstantExpression) {
            logger.debug { "ConstantExpression detected, skipping local resolution" }
            return SymbolResolutionStrategy.notFound("ConstantExpression - ignoring literals", STRATEGY_NAME)
        }

        // Special handling for ConstructorCallExpression: check if the target class is defined locally
        // CRITICAL: This must come BEFORE the general resolution to avoid incorrect resolution
        if (context.targetNode is ConstructorCallExpression) {
            val targetClass = context.targetNode.type
            if (!isLocallyDefinedClass(targetClass, context.documentUri)) {
                logger.debug {
                    "ConstructorCallExpression for ${targetClass.name} is not local, deferring to other strategies"
                }
                return SymbolResolutionStrategy.notFound(
                    "ConstructorCallExpression for external class ${targetClass.name}",
                    STRATEGY_NAME,
                )
            }
            // If the class IS locally defined, continue with normal resolution
        }

        // Special handling for ClassNode: check if it's defined locally
        if (context.targetNode is ClassNode) {
            return resolveClassNode(context.targetNode, context)
        }

        val definition = try {
            context.targetNode.resolveToDefinition(astVisitor, symbolTable, strict = false)
        } catch (e: StackOverflowError) {
            logger.debug(e) { "Stack overflow during local resolution, likely circular reference" }
            // NOTE: The legacy resolver surfaced a dedicated exception with additional context. In the new pipeline,
            // we keep the resolution non-fatal and return a typed error code for debugging.
            // TODO: Capture the resolution path deterministically (without relying on StackOverflowError) so we can
            // provide actionable circular reference details.
            return SymbolResolutionStrategy.notFound(
                "Error[CIRCULAR_REFERENCE]: stack overflow during local resolution",
                STRATEGY_NAME,
            )
        } catch (e: IllegalArgumentException) {
            logger.debug(e) { "Invalid argument during local resolution: ${e.message}" }
            return SymbolResolutionStrategy.notFound("Invalid argument: ${e.message}", STRATEGY_NAME)
        } catch (e: IllegalStateException) {
            logger.debug(e) { "Invalid state during local resolution: ${e.message}" }
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
                // For cross-file resolution, if the class is NOT in the current document, return null
                // so the pipeline continues to SemanticDB or GlobalClass strategies.
                logger.debug {
                    "Attempting to resolve ClassNode locally: ${definition.name} (from ${context.targetNode.javaClass.simpleName})"
                }
                logger.debug {
                    "ClassNode URI: ${astVisitor.getUri(definition)}, Current document: ${context.documentUri}"
                }
                logger.debug { "ClassNode position: ${definition.lineNumber}:${definition.columnNumber}" }
                if (!isLocallyDefinedClass(definition, context.documentUri)) {
                    logger.debug {
                        "ClassNode ${definition.name} is not in current document ${context.documentUri}, deferring to other strategies"
                    }
                    null
                } else {
                    logger.debug { "ClassNode ${definition.name} IS local, resolving locally" }
                    resolveLocalClassDefinition(definition, context.documentUri)
                }
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

        logger.debug {
            "Resolved local definition: ${filteredDefinition.javaClass.simpleName} at ${filteredDefinition.lineNumber}:${filteredDefinition.columnNumber}"
        }

        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Source(filteredDefinition, context.documentUri),
        )
    }

    /**
     * Handle ClassNode directly - only resolve if it's defined in the current document.
     */
    private fun resolveClassNode(classNode: ClassNode, context: ResolutionContext): ResolutionResult {
        // Check if this class is defined locally (exists in the same file)
        if (!isLocallyDefinedClass(classNode, context.documentUri)) {
            logger.debug { "ClassNode ${classNode.name} is an external reference, skipping local resolution" }
            return SymbolResolutionStrategy.notFound(
                "ClassNode ${classNode.name} is an external reference",
                STRATEGY_NAME,
            )
        }

        val localClass = resolveLocalClassDefinition(classNode, context.documentUri)
        if (localClass == null || !hasValidPosition(localClass)) {
            return SymbolResolutionStrategy.notFound(
                "Class ${classNode.name} not found in local AST",
                STRATEGY_NAME,
            )
        }

        logger.debug {
            "Resolved local class definition: ${localClass.name} at ${localClass.lineNumber}:${localClass.columnNumber}"
        }
        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Source(localClass, context.documentUri),
        )
    }

    /**
     * Check if a class is defined in the current document's AST.
     *
     * A class is "locally defined" only if its definition exists in the same file
     * as the current document. External classes (from JARs, JRT, or other workspace files)
     * should be resolved by SemanticDB or GlobalClass strategies.
     */
    private fun isLocallyDefinedClass(classNode: ClassNode, currentUri: URI): Boolean {
        // Get the URI where this ClassNode is defined
        val classUri = astVisitor.getUri(classNode)

        logger.info { "=== isLocallyDefinedClass check ===" }
        logger.info { "ClassNode: ${classNode.name} at ${classNode.lineNumber}:${classNode.columnNumber}" }
        logger.info { "ClassNode URI from astVisitor: $classUri" }
        logger.info { "Current document URI: $currentUri" }
        logger.info { "ClassNode identity hash: ${System.identityHashCode(classNode)}" }

        // CRITICAL: If we can't find the URI, it means this ClassNode is not tracked in our AST model,
        // which means it's NOT locally defined. Don't proceed to fallback checks - just return false.
        if (classUri == null) {
            logger.debug {
                "ClassNode ${classNode.name} has no URI in AST model, not local to $currentUri"
            }
            // ONLY proceed if classNode is a reference type (no source location)
            // Check the final fallback: search by name in current document
            // CRITICAL FIX: Only consider ClassNodes that are actual class DEFINITIONS (with valid position),
            // not ClassNode references from constructor calls or variable declarations.
            // Without this filter, we might find the ClassNode reference from `new Calculator(10)` which
            // is tracked in Main.groovy's AST, causing us to incorrectly think Calculator is defined locally.
            val localClasses = astVisitor.getAllClassNodes()
            val foundLocally = localClasses.any { localClass ->
                localClass.name == classNode.name &&
                    astVisitor.getUri(localClass) == currentUri &&
                    hasValidPosition(localClass) // Must be an actual class definition, not a reference
            }
            logger.debug {
                "ClassNode ${classNode.name} foundLocally=$foundLocally in $currentUri (fallback search)"
            }
            return foundLocally
        }

        if (classUri != currentUri) {
            // Class is defined in a different file - not local
            logger.debug {
                "ClassNode ${classNode.name} is defined in $classUri (current: $currentUri), not local"
            }
            return false
        }

        // CRITICAL FIX: Even if the URI matches, we must verify this is an actual class DEFINITION
        // (from ModuleNode.classes), not just a ClassNode reference from a constructor call or variable declaration.
        // The RecursiveAstVisitor tracks ClassNode references (e.g., `new Calculator(10)`) with the current URI,
        // but these are NOT class definitions. We must check if the class is in getAllClassNodes() which
        // only returns actual class definitions from ModuleNode.classes.
        val allClassNodes = astVisitor.getAllClassNodes()
        logger.info { "All class nodes in AST model: ${allClassNodes.map { it.name }}" }
        val isActualDefinition = allClassNodes.any { localClass ->
            val match = localClass === classNode || // Same instance
                (localClass.name == classNode.name && astVisitor.getUri(localClass) == currentUri)
            if (match) {
                logger.info {
                    "Found matching class definition: ${localClass.name} (identity match: ${localClass === classNode})"
                }
            }
            match
        }
        logger.info { "Is actual definition: $isActualDefinition" }
        if (!isActualDefinition) {
            logger.info {
                "ClassNode ${classNode.name} has URI $classUri but is not a class definition (just a tracked reference)"
            }
            return false
        }

        // Verify it has a valid position
        if (!hasValidPosition(classNode)) {
            logger.debug {
                "ClassNode ${classNode.name} has URI $classUri but no valid position"
            }
            return false
        }

        // Also check the redirected class (canonical definition)
        val redirected = classNode.redirect()
        if (redirected !== classNode) {
            val redirectedUri = astVisitor.getUri(redirected)
            if (redirectedUri != null && redirectedUri != currentUri) {
                logger.debug {
                    "ClassNode ${classNode.name} redirects to ${redirected.name} in $redirectedUri (current: $currentUri), not local"
                }
                return false
            }
        }

        // Class has URI matching current document AND valid position - it's local
        logger.debug {
            "ClassNode ${classNode.name} is local to $currentUri with valid position"
        }
        return true
    }

    private fun resolveLocalClassDefinition(classNode: ClassNode, currentUri: URI): ClassNode? {
        val redirected = classNode.redirect()
        if (redirected !== classNode &&
            isLocallyDefinedClass(redirected, currentUri) &&
            hasValidPosition(redirected)
        ) {
            return redirected
        }
        return findLocalClassDeclaration(classNode.name, currentUri)
    }

    private fun findLocalClassDeclaration(className: String, currentUri: URI): ClassNode? =
        astVisitor.getAllClassNodes()
            .asSequence()
            .filter { cls ->
                cls.name == className &&
                    hasValidPosition(cls) &&
                    astVisitor.getUri(cls) == currentUri
            }
            .minByOrNull { it.lineNumber }

    private fun hasValidPosition(node: ASTNode): Boolean =
        node.lineNumber > 0 && node.columnNumber > 0 && node.lastLineNumber > 0 && node.lastColumnNumber > 0

    companion object {
        private const val STRATEGY_NAME = "LocalSymbol"
    }
}
