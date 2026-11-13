// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.slf4j.LoggerFactory

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
    private val semanticResolver = com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver(
        compilationService.classpathService.getTypeSolver(),
        compilationService.getWorkspaceSymbolIndex(),
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
            .singleOrNull {
                it.name == methodName && it.isPublic && (argCount == null || it.parameters.size == argCount)
            }
            ?: return SymbolResolutionStrategy.notFound(
                "Method $methodName not found on type $receiverType",
                STRATEGY_NAME,
            )

        logger.debug("Found method {} in classpath", method.name)

        // Find the classpath URI for the declaring class
        val requestContext = currentCoroutineContext()
        val request = compilationService.captureSourceRequest(
            context.documentUri,
            method.declaringClass,
            methodName,
            checkCancelled = requestContext::ensureActive,
        )
            ?: return SymbolResolutionStrategy.notFound(
                "Class ${method.declaringClass} not found on classpath",
                STRATEGY_NAME,
            )

        // Try to navigate to method source if possible
        sourceNavigator?.let { navigator ->
            return sourceResolution(
                navigator.locate(request.symbol, request.context),
                "${method.declaringClass}#$methodName",
                STRATEGY_NAME,
            )
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

    companion object {
        private const val STRATEGY_NAME = "Method"
    }
}
