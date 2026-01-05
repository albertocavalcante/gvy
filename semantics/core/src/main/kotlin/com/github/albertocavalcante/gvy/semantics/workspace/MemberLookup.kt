package com.github.albertocavalcante.gvy.semantics.workspace

import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind

/**
 * Interface for looking up class members (fields, methods) across the workspace.
 * This abstraction allows NativeTypeContext to resolve cross-file types without
 * depending directly on the LSP layer's WorkspaceSymbolIndex.
 *
 * Implementations should provide efficient lookup of symbols by class FQN and member name.
 */
interface MemberLookup {
    /**
     * Find a field in a class.
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @param fieldName The simple field name
     * @return Member information if found, null otherwise
     */
    fun findField(classFqn: String, fieldName: String): MemberInfo?

    /**
     * Find a method in a class.
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @param methodName The simple method name
     * @param arity The number of parameters, or null to match any arity
     * @return Member information if found, null otherwise
     */
    fun findMethod(classFqn: String, methodName: String, arity: Int? = null): MemberInfo?

    /**
     * Get all members of a class (fields, methods, properties).
     *
     * @param classFqn The fully qualified class name (e.g., "com/example/MyClass")
     * @param includeInherited Whether to include inherited members
     * @return List of all member information
     */
    fun getAllMembers(classFqn: String, includeInherited: Boolean = true): List<MemberInfo>
}

/**
 * Information about a class member (field, method, or property).
 *
 * @property name Simple name of the member (e.g., "myField", "myMethod")
 * @property kind The kind of member (FIELD, METHOD, PROPERTY)
 * @property type The semantic type of the member (field type or method return type), null if not available
 * @property signature The method signature for methods, null for fields
 * @property symbolId The unique symbol ID in SemanticDB format
 */
data class MemberInfo(
    val name: String,
    val kind: SymbolKind,
    val type: SemanticType?,
    val signature: String?,
    val symbolId: String,
)
