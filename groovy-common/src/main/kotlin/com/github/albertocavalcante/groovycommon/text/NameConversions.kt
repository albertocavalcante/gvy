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
 * Formats a type name to be more readable in inlay hints by simplifying fully qualified names
 * and handling generic type parameters recursively.
 *
 * @receiver The fully qualified type name string.
 * @return A simplified type name string suitable for inlay hints.
 */
fun String.formatTypeName(): String {
    if (isEmpty()) return ""
    if (contains('<')) {
        val baseName = substringBefore('<').simpleClassName()
        val typeParamsStr = substringAfter('<').substringBeforeLast('>')
        val typeParams = splitByTopLevelComma(typeParamsStr).map { it.trim().formatTypeName() }
        return "$baseName<${typeParams.joinToString(", ")}>"
    }
    // Handle bounded wildcards or multi-word types (e.g. "? extends java.lang.Number")
    if (contains(' ')) {
        return split(' ').joinToString(" ") { it.formatTypeName() }
    }
    return simpleClassName()
}

/**
 * Splits a generic type parameter string by top-level commas, respecting nested angle brackets.
 *
 * @param s The string containing comma-separated type parameters.
 * @return A list of individual type parameter strings.
 */
private fun splitByTopLevelComma(s: String): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var depth = 0
    for (char in s) {
        when (char) {
            '<' -> depth++
            '>' -> depth--
            ',' -> if (depth == 0) {
                result.add(current.toString())
                current = StringBuilder()
                continue
            }
        }
        current.append(char)
    }
    result.add(current.toString())
    return result
}
