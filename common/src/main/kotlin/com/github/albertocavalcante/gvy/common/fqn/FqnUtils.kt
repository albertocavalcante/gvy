package com.github.albertocavalcante.gvy.common.fqn

/**
 * Utilities for working with Fully Qualified Names (FQN).
 *
 * Provides functions for:
 * - Extracting package names from class names
 * - Extracting simple class names
 * - Parsing and manipulating FQNs
 *
 * All functions are pure, stateless, and handle edge cases gracefully.
 *
 * Note: This complements `groovycommon.text.NameConversions.simpleClassName()`,
 * which extracts simple names. This module focuses on package manipulation.
 */

/**
 * Extracts the package name from a fully qualified class name.
 *
 * This function:
 * - Returns the package portion before the last dot
 * - Returns empty string if no package (e.g., "String" → "")
 * - Handles classes with dots in their package path
 * - Works with both `.` and `/` separators
 *
 * Examples:
 * - `"java.util.ArrayList"` → `"java.util"`
 * - `"java/util/ArrayList"` → `"java/util"`
 * - `"String"` → `""`
 * - `"com.example.MyClass"` → `"com.example"`
 * - `""` → `""`
 *
 * @param className The fully qualified class name
 * @return The package name, or empty string if no package
 */
fun packageName(className: String): String {
    if (className.isEmpty()) return ""

    // Handle both . and / separators
    val separator = when {
        className.contains('.') -> '.'
        className.contains('/') -> '/'
        else -> return "" // No package
    }

    return className.substringBeforeLast(separator)
}

/**
 * Extracts the package name from a fully qualified class name.
 * This is an alias for packageName() for better discoverability.
 *
 * @param className The fully qualified class name
 * @return The package name, or empty string if no package
 */
fun String.toPackageName(): String = packageName(this)
