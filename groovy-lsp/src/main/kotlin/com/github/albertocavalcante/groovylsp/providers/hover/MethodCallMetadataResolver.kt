package com.github.albertocavalcante.groovylsp.providers.hover

import com.github.albertocavalcante.groovylsp.services.ClasspathService
import com.github.albertocavalcante.groovylsp.services.GdkExtensionMethod
import com.github.albertocavalcante.groovylsp.services.GroovyGdkProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.slf4j.LoggerFactory

/**
 * Resolves method call metadata from classpath and GDK when AST metadata is unavailable.
 * Provides fallback hover information for method calls that don't have compile-time metadata.
 */
class MethodCallMetadataResolver(
    private val classpathService: ClasspathService,
    private val gdkProvider: GroovyGdkProvider,
    private val semanticResolver: SemanticTypeResolver,
) {
    private val logger = LoggerFactory.getLogger(MethodCallMetadataResolver::class.java)

    /**
     * Represents resolved method metadata for hover display.
     */
    data class MethodMetadata(
        val signature: String,
        val declaringClass: String,
        val returnType: String,
        val documentation: String?,
    )

    /**
     * Attempts to resolve method call metadata from GDK or classpath.
     *
     * @param call The method call expression to resolve
     * @param moduleNode The module node containing the method call (optional)
     * @return Resolved metadata if found, null otherwise
     */
    fun resolveMethodCall(call: MethodCallExpression, moduleNode: ModuleNode?): MethodMetadata? {
        val methodName = call.methodAsString
        if (methodName.isNullOrBlank()) {
            logger.debug("Method name is null or blank")
            return null
        }

        val receiverType = resolveReceiverType(call, moduleNode) ?: return null

        // Get argument count from method call for better overload resolution
        val argCount = when (val args = call.arguments) {
            is ArgumentListExpression -> args.expressions.size
            is TupleExpression -> args.expressions.size
            else -> null
        }

        // 1. Try GDK first (higher priority for Groovy extension methods)
        gdkProvider.getMethodsForType(receiverType)
            .find { it.name == methodName && (argCount == null || it.parameterTypes.size == argCount) }
            ?.let { return it.toMethodMetadata() }

        // 2. Then classpath
        classpathService.getMethods(receiverType)
            .find { it.name == methodName && it.isPublic && (argCount == null || it.parameters.size == argCount) }
            ?.let { return it.toMethodMetadata() }

        return null
    }

    /**
     * Resolves the receiver type of a method call.
     */
    // TODO(#650): This logic is duplicated in MethodResolutionStrategy. Extract to shared utility.
    //   See: https://github.com/albertocavalcante/groovy-lsp/issues/650
    private fun resolveReceiverType(call: MethodCallExpression, moduleNode: ModuleNode?): String? {
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
            val semanticType = semanticResolver.resolveType(objectExpr, moduleNode)
            semanticResolver.formatSemanticType(semanticType)
        } catch (e: Exception) {
            logger.debug("Failed to resolve receiver type for method call", e)
            null
        }
    }

    /**
     * Converts a GDK extension method to method metadata.
     */
    // TODO(#650): Parameter name handling logic is duplicated in toMethodMetadata extensions.
    //   Consider extracting to shared utility for consistent parameter formatting.
    private fun GdkExtensionMethod.toMethodMetadata(): MethodMetadata {
        // Use mapIndexed to avoid truncation when parameterNames.size < parameterTypes.size
        val params = parameterTypes.mapIndexed { i, type ->
            val paramName = parameterNames.getOrNull(i) ?: "arg$i"
            "$type $paramName"
        }.joinToString(", ")
        val signature = "$returnType $name($params)"
        return MethodMetadata(
            signature = signature,
            declaringClass = originClass,
            returnType = returnType,
            documentation = doc,
        )
    }

    /**
     * Converts a classpath method to method metadata.
     */
    private fun com.github.albertocavalcante.groovylsp.services.ReflectedMethod.toMethodMetadata(): MethodMetadata {
        // Use mapIndexed to avoid truncation when parameterNames.size < parameters.size
        val params = parameters.mapIndexed { i, type ->
            val paramName = parameterNames.getOrNull(i) ?: "arg$i"
            "$type $paramName"
        }.joinToString(", ")
        val signature = "$returnType $name($params)"
        return MethodMetadata(
            signature = signature,
            declaringClass = declaringClass,
            returnType = returnType,
            documentation = doc,
        )
    }
}
