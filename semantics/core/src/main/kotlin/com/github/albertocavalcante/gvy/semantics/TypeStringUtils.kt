package com.github.albertocavalcante.gvy.semantics

/**
 * Utility functions for type string validation and normalization.
 * Single source of truth for type name handling across the codebase.
 */
object TypeStringUtils {
    /**
     * Check if a type name represents a dynamic/unknown type.
     * Handles both exact matches and prefix patterns like "unresolved variable: foo".
     *
     * @param typeName The type name to check
     * @return true if the type represents a dynamic/unknown type
     */
    fun isDynamicType(typeName: String): Boolean = typeName in TypeNames.DYNAMIC_TYPE_NAMES ||
        typeName.startsWith("unresolved", ignoreCase = true)

    /**
     * Check if a type name is valid for classpath lookup (Class.forName).
     * Invalid patterns:
     * - Contains spaces or colons (indicates error/placeholder type like "unresolved variable: foo")
     * - Empty or blank
     * - Unresolved/dynamic types
     *
     * @param typeName The type name to validate
     * @return true if the type name can be safely passed to Class.forName
     */
    fun isValidClasspathTypeName(typeName: String): Boolean {
        if (typeName.isBlank()) return false
        if (typeName.contains(' ') || typeName.contains(':')) return false
        if (isDynamicType(typeName)) return false
        return true
    }

    /**
     * Normalize a type name by stripping generic parameters.
     * Example: "List<String>" becomes "List"
     *
     * @param typeName The type name to normalize
     * @return The normalized type name without generics
     */
    fun normalizeTypeName(typeName: String): String = typeName.substringBefore('<')

    /**
     * Check if a type name is an unknown/unresolved type (subset of dynamic).
     *
     * @param typeName The type name to check, can be null
     * @return true if the type is null or represents an unknown type
     */
    fun isUnknownType(typeName: String?): Boolean {
        if (typeName == null) return true
        val normalized = normalizeTypeName(typeName)
        return normalized == TypeNames.JAVA_LANG_OBJECT ||
            normalized == TypeNames.OBJECT ||
            normalized == TypeNames.DEF
    }
}
