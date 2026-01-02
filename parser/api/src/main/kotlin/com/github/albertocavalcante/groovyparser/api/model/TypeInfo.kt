package com.github.albertocavalcante.groovyparser.api.model

/**
 * Type information for an expression or declaration.
 */
data class TypeInfo(
    val name: String,
    val qualifiedName: String? = null,
    val isResolved: Boolean = true,
    val genericTypes: List<TypeInfo> = emptyList(),
) {
    companion object {
        val UNKNOWN = TypeInfo("?", isResolved = false)
        val VOID = TypeInfo("void", "void")
        val OBJECT = TypeInfo("Object", "java.lang.Object")
        val STRING = TypeInfo("String", "java.lang.String")
        val INT = TypeInfo("int", "int")
        val BOOLEAN = TypeInfo("boolean", "boolean")
    }
}
