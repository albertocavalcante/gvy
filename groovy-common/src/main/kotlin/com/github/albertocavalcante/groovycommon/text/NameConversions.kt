package com.github.albertocavalcante.groovycommon.text

/**
 * Deterministic name conversion utilities for class and step name manipulation.
 *
 * Centralizes common string transformations used across multiple modules:
 * - `groovy-jenkins`: Step name derivation from class names
 * - `groovy-lsp`: Hover display, completion providers
 *
 * All functions are pure, stateless, and have no side effects.
 */

/**
 * Extracts the simple class name from a fully qualified name.
 *
 * Examples:
 * - `"java.util.ArrayList"` → `"ArrayList"`
 * - `"String"` → `"String"`
 * - `""` → `""`
 */
fun String.simpleClassName(): String = substringAfterLast('.')

/**
 * Converts the first character to lowercase (lowerCamelCase).
 *
 * Examples:
 * - `"MyClass"` → `"myClass"`
 * - `"already"` → `"already"`
 * - `"A"` → `"a"`
 */
fun String.toLowerCamelCase(): String = replaceFirstChar { it.lowercase() }

/**
 * Derives a step name from a class name following Jenkins conventions.
 *
 * Removes common suffixes (Step, Builder, Descriptor) and converts to lowerCamelCase.
 *
 * Examples:
 * - `"ShellStep"` → `"shell"`
 * - `"DockerBuilder"` → `"docker"`
 * - `"GitDescriptor"` → `"git"`
 */
fun String.toStepName(): String = this
    .removeSuffix("Step")
    .removeSuffix("Builder")
    .removeSuffix("Descriptor")
    .toLowerCamelCase()

/**
 * Converts a setter method name to property name.
 *
 * Examples:
 * - `"setUserName"` → `"userName"`
 * - `"setX"` → `"x"`
 * - `"notASetter"` → `"notASetter"` (no change if no "set" prefix)
 */
fun String.toPropertyName(): String = removePrefix("set").toLowerCamelCase()

/**
 * Extracts symbol name from annotation values.
 *
 * Handles various types returned by ClassGraph annotation parsing:
 * - String: returned directly
 * - Array<*>: first non-blank element
 * - List<*>: first non-blank element
 * - null: returns null
 *
 * @param value The annotation value (String, Array, List, or null)
 * @return The extracted symbol name, or null if not extractable
 */
fun extractSymbolName(value: Any?): String? = when (value) {
    is String -> value.takeIf { it.isNotBlank() }
    is Array<*> -> value.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
    is List<*> -> value.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
    else -> {
        // ClassGraph may return other types - try toString() as fallback
        val str = value?.toString()
        str?.takeIf { it.isNotBlank() && !it.contains("@") }
    }
}

/**
 * Formats a fully qualified type name to a more readable simple form.
 *
 * Handles both simple types and generic types with type parameters.
 *
 * Examples:
 * - `"java.lang.String"` → `"String"`
 * - `"java.util.ArrayList<java.lang.Integer>"` → `"ArrayList<Integer>"`
 * - `"java.util.Map<java.lang.String, java.lang.Object>"` → `"Map<String, Object>"`
 * - `"String"` → `"String"` (already simple)
 * - `""` → `""`
 *
 * @return The simplified type name with package names removed
 */
fun String.formatTypeName(): String {
    if (isEmpty()) return this

    return if (contains('<')) {
        val baseName = substringBefore('<').simpleClassName()
        val typeParams = substringAfter('<').substringBeforeLast('>')
        val formattedParams = typeParams.split(',').joinToString(", ") {
            it.trim().formatTypeName() // Recursive for nested generics
        }
        "$baseName<$formattedParams>"
    } else {
        simpleClassName()
    }
}
