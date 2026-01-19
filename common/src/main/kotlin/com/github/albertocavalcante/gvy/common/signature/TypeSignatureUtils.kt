package com.github.albertocavalcante.gvy.common.signature

/**
 * Utilities for parsing and analyzing type signatures.
 *
 * Provides functions for extracting information from method signatures, including:
 * - Parameter counting (respecting generics)
 * - Parameter type extraction
 * - Signature parsing with generic type support
 *
 * All functions are pure, stateless, and handle edge cases gracefully.
 */

/**
 * Extracts the number of parameters from a method signature, respecting angle brackets for generics.
 *
 * This function parses method signatures in the format:
 * - `"com/example/MyClass#myMethod()."` → 0 parameters
 * - `"com/example/MyClass#myMethod(String)."` → 1 parameter
 * - `"com/example/MyClass#myMethod(String,int)."` → 2 parameters
 * - `"com/example/MyClass#myMethod(Map<String,String>)."` → 1 parameter (not 2!)
 * - `"com/example/MyClass#myMethod(List<Map<String,Integer>>,String)."` → 2 parameters
 *
 * The function correctly handles:
 * - Empty parameter lists
 * - Simple types
 * - Generic types with angle brackets
 * - Nested generics
 * - Varargs
 * - Malformed signatures (returns 0)
 *
 * @param signature The method signature string
 * @return The number of parameters, or 0 if the signature is invalid
 */
fun extractParameterCount(signature: String): Int {
    val startIndex = signature.indexOf('(')
    val endIndex = signature.indexOf(')')

    if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
        return 0
    }

    val params = signature.substring(startIndex + 1, endIndex)
    if (params.isEmpty()) return 0

    // Split by comma while respecting angle brackets for generics
    var count = 0
    var bracketDepth = 0
    var invalidBrackets = false
    for (char in params) {
        when (char) {
            '<' -> bracketDepth++
            '>' -> {
                bracketDepth--
                if (bracketDepth < 0) {
                    invalidBrackets = true
                    break
                }
            }
            ',' -> if (bracketDepth == 0) count++
        }
    }

    // If brackets are unbalanced, fall back to a simple comma-based count
    if (invalidBrackets || bracketDepth != 0) {
        return params.split(',')
            .map { it.trim() }
            .count { it.isNotEmpty() }
    }

    // Add 1 for the last parameter (no trailing comma)
    return count + 1
}

/**
 * Parses a method signature and extracts parameter types as a list of strings.
 *
 * This function extracts parameter types from method signatures, simplifying fully qualified names
 * to simple class names. It correctly handles:
 * - Empty parameter lists → empty list
 * - Simple types → ["String", "int"]
 * - Generic types → ["Map<String,String>"]
 * - Nested generics → ["List<Map<String,Integer>>"]
 * - Fully qualified names are simplified to simple names
 *
 * Examples:
 * - `"com/example/MyClass#myMethod()."` → []
 * - `"com/example/MyClass#myMethod(java.lang.String)."` → ["String"]
 * - `"com/example/MyClass#myMethod(String,int)."` → ["String", "int"]
 * - `"com/example/MyClass#myMethod(Map<String,String>)."` → ["Map<String,String>"]
 * - `"com/example/MyClass#myMethod(java/util/Map<String,String>,int)."` → ["Map<String,String>", "int"]
 *
 * @param signature The method signature (may be null)
 * @return List of parameter type strings (simplified), or empty list if signature is null/invalid
 */
fun parseSignatureParameters(signature: String?): List<String> {
    if (signature == null) return emptyList()

    val startIndex = signature.indexOf('(')
    val endIndex = signature.indexOf(')')

    if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
        return emptyList()
    }

    val params = signature.substring(startIndex + 1, endIndex).trim()
    if (params.isEmpty()) return emptyList()

    // Split by comma while respecting angle brackets for generics
    val result = mutableListOf<String>()
    val currentParam = StringBuilder()
    var bracketDepth = 0
    var invalidBrackets = false

    for (char in params) {
        when (char) {
            '<' -> {
                bracketDepth++
                currentParam.append(char)
            }
            '>' -> {
                bracketDepth--
                if (bracketDepth < 0) {
                    invalidBrackets = true
                    break
                }
                currentParam.append(char)
            }
            ',' -> {
                if (bracketDepth == 0) {
                    result.add(simplifyTypeName(currentParam.toString().trim()))
                    currentParam.clear()
                } else {
                    currentParam.append(char)
                }
            }
            else -> currentParam.append(char)
        }
    }

    // If brackets are unbalanced, fall back to simple comma-based parsing
    if (invalidBrackets || bracketDepth != 0) {
        return params.split(',')
            .map { simplifyTypeName(it.trim()) }
            .filter { it.isNotEmpty() }
    }

    // Add the last parameter
    if (currentParam.isNotEmpty()) {
        result.add(simplifyTypeName(currentParam.toString().trim()))
    }

    return result
}

/**
 * Simplifies a fully qualified type name to its simple name.
 * Handles special cases like varargs (String...).
 *
 * @param typeName The fully qualified type name (e.g., "java.lang.String" or "String...")
 * @return The simplified type name (e.g., "String" or "String...")
 */
private fun simplifyTypeName(typeName: String): String {
    // Handle varargs: preserve "..." suffix
    if (typeName.endsWith("...")) {
        val baseType = typeName.removeSuffix("...")
        val simplified = baseType.substringAfterLast('/').substringAfterLast('.')
        return "$simplified..."
    }
    // Standard simplification
    return typeName.substringAfterLast('/').substringAfterLast('.')
}
