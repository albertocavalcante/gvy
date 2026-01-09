package com.github.albertocavalcante.gvy.semantics

/**
 * Centralized formatter for SemanticType instances.
 * Consolidates type formatting logic previously duplicated across
 * SemanticTypeResolver, CompletionProvider, InlayHintsProvider, HoverContentGenerator,
 * and SignatureHelpProvider.
 */
object SemanticTypeFormatter {

    /**
     * Format a SemanticType for display with configurable options.
     *
     * @param type The semantic type to format
     * @param options Formatting options (default: simple name, with generics)
     * @return A formatted type string
     */
    fun format(type: SemanticType, options: FormatOptions = FormatOptions.DEFAULT): String = when (type) {
        is SemanticType.Known -> formatKnownType(type, options)
        is SemanticType.Primitive -> formatPrimitiveType(type.kind)
        is SemanticType.Dynamic -> formatDynamicType(type)
        is SemanticType.Unknown -> TypeNames.DEF // Groovy's dynamic type keyword
        is SemanticType.Union -> formatUnionType(type, options)
        is SemanticType.Null -> TypeNames.NULL
        is SemanticType.Array -> formatArrayType(type, options)
    }

    /**
     * Format a SemanticType for completion items.
     * Uses simple names with generics for readability.
     *
     * @param type The semantic type to format
     * @return A formatted type string suitable for completion items
     */
    fun formatForCompletion(type: SemanticType): String = format(
        type,
        FormatOptions(includePackage = false, includeGenerics = true),
    )

    /**
     * Format a SemanticType for hover information.
     * Uses simple names with generics for readability.
     *
     * @param type The semantic type to format
     * @return A formatted type string suitable for hover display
     */
    fun formatForHover(type: SemanticType): String = format(
        type,
        FormatOptions(includePackage = false, includeGenerics = true),
    )

    /**
     * Format a SemanticType to fully qualified name for classpath lookups.
     * Returns the FQN for Known types, primitive names for primitives,
     * and "java.lang.Object" for dynamic/unknown types.
     *
     * @param type The semantic type to format
     * @return A fully qualified name suitable for reflection/classpath operations
     */
    fun formatToFqn(type: SemanticType): String = when (type) {
        is SemanticType.Known -> type.fqn
        is SemanticType.Primitive -> formatPrimitiveType(type.kind)
        is SemanticType.Dynamic -> TypeNames.JAVA_LANG_OBJECT
        is SemanticType.Unknown -> TypeNames.JAVA_LANG_OBJECT
        is SemanticType.Null -> TypeNames.JAVA_LANG_OBJECT
        is SemanticType.Union -> {
            // Return first known type, or Object as fallback
            type.types.firstNotNullOfOrNull {
                when (it) {
                    is SemanticType.Known -> it.fqn
                    else -> null
                }
            } ?: TypeNames.JAVA_LANG_OBJECT
        }
        is SemanticType.Array -> {
            val componentFqn = formatToFqn(type.componentType)
            "$componentFqn[]"
        }
    }

    private fun formatKnownType(type: SemanticType.Known, options: FormatOptions): String {
        val baseName = if (options.includePackage) {
            type.fqn
        } else {
            type.fqn.substringAfterLast('.')
        }

        return if (options.includeGenerics && type.typeArgs.isNotEmpty()) {
            val formattedArgs = type.typeArgs.joinToString(", ") { format(it, options) }
            "$baseName<$formattedArgs>"
        } else {
            baseName
        }
    }

    private fun formatPrimitiveType(kind: PrimitiveKind): String = kind.name.lowercase()

    private fun formatDynamicType(type: SemanticType.Dynamic): String = type.hint ?: TypeNames.DEF

    private fun formatUnionType(type: SemanticType.Union, options: FormatOptions): String {
        val formatted = type.types.map { format(it, options) }.sorted()
        return formatted.joinToString(" | ")
    }

    private fun formatArrayType(type: SemanticType.Array, options: FormatOptions): String =
        "${format(type.componentType, options)}[]"
}

/**
 * Formatting options for SemanticType display.
 *
 * @property includePackage If true, use fully qualified names; if false, use simple names
 * @property includeGenerics If true, include generic type parameters; if false, omit them
 */
data class FormatOptions(val includePackage: Boolean = false, val includeGenerics: Boolean = true) {
    companion object {
        /**
         * Default formatting options: simple names with generics.
         */
        val DEFAULT = FormatOptions(includePackage = false, includeGenerics = true)

        /**
         * Fully qualified formatting: FQNs with generics.
         */
        val FULLY_QUALIFIED = FormatOptions(includePackage = true, includeGenerics = true)

        /**
         * Simple formatting: simple names without generics.
         */
        val SIMPLE = FormatOptions(includePackage = false, includeGenerics = false)
    }
}
