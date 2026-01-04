package com.github.albertocavalcante.gvy.semantics.calculator

import com.github.albertocavalcante.gvy.semantics.SemanticType

/**
 * Context for type calculations.
 * Provides access to type resolution, scope information, and caching.
 */
interface TypeContext {

    /**
     * Resolve a type by its fully qualified name.
     *
     * @param fqn Fully qualified name, e.g., "java.util.List"
     * @return The resolved type, or [SemanticType.Unknown] if not found
     */
    fun resolveType(fqn: String): SemanticType

    /**
     * Recursively calculate type of another node.
     * Use this when your calculator needs the type of a child node.
     *
     * @param node The node to calculate type for
     * @return The calculated type
     */
    fun calculateType(node: Any): SemanticType

    /**
     * Look up a symbol by name in the current scope.
     *
     * @param name The symbol name
     * @return The symbol's type, or null if not found
     */
    fun lookupSymbol(name: String): SemanticType?

    /**
     * Get method return type.
     *
     * @param receiverType The type of the receiver (object on which method is called)
     * @param methodName The method name
     * @param argumentTypes Types of the arguments
     * @return The return type, or null if method not found
     */
    fun getMethodReturnType(
        receiverType: SemanticType,
        methodName: String,
        argumentTypes: List<SemanticType>,
    ): SemanticType?

    /**
     * Get field/property type.
     *
     * @param receiverType The type of the receiver
     * @param fieldName The field or property name
     * @return The field type, or null if not found
     */
    fun getFieldType(receiverType: SemanticType, fieldName: String): SemanticType?

    /**
     * Whether we're in a @CompileStatic context.
     * Affects how strictly types are resolved.
     */
    val isStaticCompilation: Boolean
}
