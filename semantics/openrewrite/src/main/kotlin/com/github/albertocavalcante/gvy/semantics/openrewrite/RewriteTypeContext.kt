package com.github.albertocavalcante.gvy.semantics.openrewrite

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeConstants
import com.github.albertocavalcante.gvy.semantics.calculator.TypeContext
import org.openrewrite.java.tree.TypedTree

/**
 * TypeContext implementation for OpenRewrite LST.
 *
 * Provides semantic type analysis for OpenRewrite refactoring recipes.
 * Bridges between OpenRewrite's JavaType system and the semantics layer's SemanticType.
 *
 * Usage in OpenRewrite recipes:
 * ```kotlin
 * class MyRecipe : Recipe() {
 *     override fun getVisitor(): TreeVisitor<*, ExecutionContext> = object : GroovyVisitor<ExecutionContext>() {
 *         private val typeContext = RewriteTypeContext()
 *
 *         override fun visitMethodInvocation(method: J.MethodInvocation, ctx: ExecutionContext): J {
 *             val receiverType = typeContext.calculateType(method.select)
 *             // Use receiverType for semantic analysis
 *             return super.visitMethodInvocation(method, ctx)
 *         }
 *     }
 * }
 * ```
 *
 * @property isStaticCompilation Whether to apply static compilation rules
 */
class RewriteTypeContext(override val isStaticCompilation: Boolean = false) : TypeContext {

    /**
     * Resolve a type by fully qualified name.
     *
     * In OpenRewrite context, this creates a Known type directly since
     * OpenRewrite handles classpath resolution internally.
     */
    override fun resolveType(fqn: String): SemanticType = SemanticType.Known(fqn)

    /**
     * Calculate the type of an OpenRewrite AST node.
     *
     * Extracts type information from the LST node and converts to SemanticType.
     */
    override fun calculateType(node: Any): SemanticType = when (node) {
        is TypedTree -> LstTypeMapper.toSemanticType(node.type)
            ?: SemanticType.Unknown("No type information for ${node::class.simpleName}")
        else -> SemanticType.Unknown("Unsupported node type: ${node::class.simpleName}")
    }

    /**
     * Look up a symbol by name.
     *
     * Not implemented in the basic OpenRewrite adapter - would require
     * integrating with OpenRewrite's symbol resolution or a custom scope.
     */
    override fun lookupSymbol(name: String): SemanticType? = null

    /**
     * Get the return type of a method call.
     *
     * Not implemented in the basic OpenRewrite adapter - would require
     * integrating with OpenRewrite's type solver or JavaParser.
     */
    override fun getMethodReturnType(
        receiverType: SemanticType,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): SemanticType? = null

    /**
     * Get the type of a field.
     *
     * Provides special handling for array length property.
     * Full field resolution would require integrating with a type solver.
     */
    override fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType? = when (receiverType) {
        is SemanticType.Array -> {
            // Arrays have a special 'length' property
            if (fieldName == "length") TypeConstants.INT else null
        }
        else -> null
    }
}
