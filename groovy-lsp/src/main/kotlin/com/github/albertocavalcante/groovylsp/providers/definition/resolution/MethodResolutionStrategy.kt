package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolves method calls to classpath methods with source navigation.
 *
 * This strategy handles method-level navigation for external dependencies:
 * - JDK methods → navigates to method in $JAVA_HOME/lib/src.zip
 * - Maven dependency methods → navigates to method in source JAR
 *
 * **Priority: After SemanticDB, before ClasspathResolutionStrategy** - provides method-level
 * navigation when class-level navigation would be insufficient.
 */
class MethodResolutionStrategy(
    private val compilationService: GroovyCompilationService,
    private val sourceNavigator: SourceNavigator?,
) : SymbolResolutionStrategy {

    private val logger = LoggerFactory.getLogger(MethodResolutionStrategy::class.java)
    private val semanticResolver = com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver(
        compilationService.classpathService.getTypeSolver(),
    )

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        // Only handle MethodCallExpression nodes
        val methodCall = context.targetNode as? MethodCallExpression
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        val methodName = methodCall.methodAsString
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        // Resolve the receiver type
        val receiverType = resolveReceiverType(methodCall, context)
            ?: return SymbolResolutionStrategy.notFound(
                "Could not resolve receiver type for method call",
                STRATEGY_NAME,
            )

        logger.debug("Resolving method {} on type {}", methodName, receiverType)

        // Get argument count from method call for better overload resolution
        val argCount = when (val args = methodCall.arguments) {
            is ArgumentListExpression -> args.expressions.size
            is TupleExpression -> args.expressions.size
            else -> null
        }

        // Find method in classpath
        val method = compilationService.classpathService.getMethods(receiverType)
            .find { it.name == methodName && it.isPublic && (argCount == null || it.parameters.size == argCount) }
            ?: return SymbolResolutionStrategy.notFound(
                "Method $methodName not found on type $receiverType",
                STRATEGY_NAME,
            )

        logger.debug("Found method {} in classpath", method.name)

        // Find the classpath URI for the declaring class
        val classpathUri = compilationService.findClasspathClass(method.declaringClass)
            ?: return SymbolResolutionStrategy.notFound(
                "Class ${method.declaringClass} not found on classpath",
                STRATEGY_NAME,
            )

        // Try to navigate to method source if possible
        sourceNavigator?.let { navigator ->
            navigateToMethodSource(navigator, classpathUri, method.declaringClass, methodName)
                ?.let { return it }
        }

        // Fallback: navigate to class-level (let ClasspathResolutionStrategy handle it)
        return SymbolResolutionStrategy.notFound(
            "Method found but source navigation not available",
            STRATEGY_NAME,
        )
    }

    /**
     * Resolves the receiver type of a method call.
     */
    // TODO(#650): This logic is duplicated in MethodCallMetadataResolver. Extract to shared utility.
    //   See: https://github.com/albertocavalcante/groovy-lsp/issues/650
    private fun resolveReceiverType(call: MethodCallExpression, context: ResolutionContext): String? {
        // Handle static calls (e.g., ClassName.method())
        val objectExpr = call.objectExpression
        if (objectExpr is ClassExpression) {
            return objectExpr.type.name
        }

        // Handle implicit this
        if (call.isImplicitThis) {
            return null // Would need AST traversal to find enclosing class
        }

        // Try to resolve the type of the receiver expression
        return try {
            val moduleNode = compilationService.getAst(context.documentUri) as? ModuleNode
            val semanticType = semanticResolver.resolveType(objectExpr, moduleNode)
            semanticResolver.formatSemanticType(semanticType)
        } catch (e: Exception) {
            logger.debug("Failed to resolve receiver type for method call", e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun navigateToMethodSource(
        sourceNavigator: SourceNavigator,
        classpathUri: URI,
        className: String,
        methodName: String,
    ): ResolutionResult? = try {
        // Navigate to the method source using the new method-level navigation
        when (val result = sourceNavigator.navigateToMethodSource(classpathUri, className, methodName)) {
            is SourceNavigator.SourceResult.SourceLocation -> {
                logger.debug("Found source for {}.{} at {}", className, methodName, result.uri)
                val range = result.lineNumber?.let(::toZeroBasedLineRange)
                SymbolResolutionStrategy.found(
                    DefinitionResolver.DefinitionResult.Binary(result.uri, "$className#$methodName", range),
                )
            }

            is SourceNavigator.SourceResult.BinaryOnly -> {
                logger.debug("No source available for {}.{}: {}", className, methodName, result.reason)
                null
            }
        }
    } catch (e: CancellationException) {
        throw e // Preserve coroutine cancellation
    } catch (e: Exception) {
        // NOTE: Source navigation is best-effort; resolution should still succeed with binaries when possible.
        logger.warn("Failed to navigate to method source for {}.{}: {}", className, methodName, e.message, e)
        null
    }

    private fun toZeroBasedLineRange(oneBasedLine: Int): Range {
        val line0 = oneBasedLine - 1
        return Range(Position(line0, 0), Position(line0, 0))
    }

    companion object {
        private const val STRATEGY_NAME = "Method"
    }
}
