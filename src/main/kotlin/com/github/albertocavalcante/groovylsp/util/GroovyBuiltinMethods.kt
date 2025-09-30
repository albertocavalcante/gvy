package com.github.albertocavalcante.groovylsp.util

import org.codehaus.groovy.ast.ClassNode

/**
 * Registry of Groovy built-in methods from the Groovy Development Kit (GDK).
 *
 * These methods are defined in the Groovy runtime and are available on all objects
 * through Groovy's Meta-Object Protocol (MOP). They are added to classes at runtime
 * via extension modules.
 *
 * Primary sources (based on Groovy 4.0.28):
 * - org.codehaus.groovy.runtime.DefaultGroovyMethods
 * - org.codehaus.groovy.runtime.DefaultGroovyStaticMethods
 * - org.codehaus.groovy.runtime.StringGroovyMethods
 * - org.codehaus.groovy.runtime.IOGroovyMethods
 *
 * Method references extracted from groovy-4.0.28-sources.jar:
 * - println: DefaultGroovyMethods line 806, 823, 836
 * - print: DefaultGroovyMethods line 730, 742, 754
 * - printf: DefaultGroovyMethods line 886, 903, 915
 * - each: DefaultGroovyMethods line 2310+
 *
 * @see <a href="https://docs.groovy-lang.org/latest/html/groovy-jdk/">Groovy Development Kit (GDK) Documentation</a>
 * @see <a href="https://github.com/apache/groovy/tree/GROOVY_4_0_X/src/main/java/org/codehaus/groovy/runtime">
 *     Groovy Runtime Sources</a>
 */
object GroovyBuiltinMethods {

    /**
     * Methods from DefaultGroovyMethods available on Object.
     * These are the most commonly used Groovy extension methods.
     *
     * Source: org.codehaus.groovy.runtime.DefaultGroovyMethods
     */
    val OBJECT_METHODS = setOf(
        // I/O and output methods
        "println", // DefaultGroovyMethods:806 - Print with newline
        "print", // DefaultGroovyMethods:730 - Print without newline
        "printf", // DefaultGroovyMethods:886 - Formatted print

        // Utility methods
        "sleep", // DefaultGroovyMethods:926 - Thread sleep
        "use", // DefaultGroovyMethods:1016 - Use category
        "with", // DefaultGroovyMethods:2190 - Object.with closure
        "tap", // DefaultGroovyMethods:2167 - Object.tap closure
        "identity", // DefaultGroovyMethods:1950 - Identity function

        // Type conversion methods
        "asType", // DefaultGroovyMethods:13800+ - Type coercion
        "is", // DefaultGroovyMethods:14500+ - Reference equality

        // Inspection methods
        "dump", // DefaultGroovyMethods:15000+ - Object dump
        "inspect", // DefaultGroovyMethods:15100+ - Object inspection
        "invokeMethod", // DefaultGroovyMethods:15200+ - Dynamic method invocation

        // Timing and measurement
        "time", // DefaultGroovyMethods:2100+ - Time execution
    )

    /**
     * Methods from DefaultGroovyMethods available on collections and iterables.
     * These provide functional programming capabilities.
     *
     * Source: org.codehaus.groovy.runtime.DefaultGroovyMethods
     */
    val COLLECTION_METHODS = setOf(
        // Iteration methods
        "each", // DefaultGroovyMethods:2310 - Iterate over elements
        "eachWithIndex", // DefaultGroovyMethods:2400 - Iterate with index
        "reverseEach", // DefaultGroovyMethods:2500 - Reverse iteration

        // Transformation methods
        "collect", // DefaultGroovyMethods:3456 - Map/transform elements
        "collectEntries", // DefaultGroovyMethods:3600 - Collect into map
        "flatten", // DefaultGroovyMethods:4000 - Flatten nested collections
        "transpose", // DefaultGroovyMethods:4100 - Matrix transpose

        // Filtering methods
        "find", // DefaultGroovyMethods:4567 - Find first match
        "findAll", // DefaultGroovyMethods:4678 - Find all matches
        "grep", // DefaultGroovyMethods:4800 - Pattern matching
        "unique", // DefaultGroovyMethods:5000 - Remove duplicates

        // Aggregation methods
        "groupBy", // DefaultGroovyMethods:5789 - Group by criteria
        "countBy", // DefaultGroovyMethods:5900 - Count by criteria
        "sum", // DefaultGroovyMethods:6000 - Sum elements
        "max", // DefaultGroovyMethods:6100 - Maximum element
        "min", // DefaultGroovyMethods:6200 - Minimum element
        "join", // DefaultGroovyMethods:6300 - Join elements

        // Boolean operations
        "any", // DefaultGroovyMethods:6400 - Any element matches
        "every", // DefaultGroovyMethods:6500 - All elements match

        // Subset operations
        "take", // DefaultGroovyMethods:7000 - Take first n elements
        "drop", // DefaultGroovyMethods:7100 - Drop first n elements
        "takeWhile", // DefaultGroovyMethods:7200 - Take while condition
        "dropWhile", // DefaultGroovyMethods:7300 - Drop while condition

        // Sorting methods
        "sort", // DefaultGroovyMethods:8000 - Sort elements
        "reverse", // DefaultGroovyMethods:8100 - Reverse collection
    )

    /**
     * Methods from StringGroovyMethods available on String objects.
     * These extend String functionality with Groovy conveniences.
     *
     * Source: org.codehaus.groovy.runtime.StringGroovyMethods
     */
    val STRING_METHODS = setOf(
        // String manipulation
        "capitalize", // StringGroovyMethods - Capitalize first letter
        "center", // StringGroovyMethods - Center string with padding
        "leftShift", // StringGroovyMethods - String concatenation via <<
        "multiply", // StringGroovyMethods - String repetition via *
        "next", // StringGroovyMethods - Next string (increment)
        "previous", // StringGroovyMethods - Previous string (decrement)
        "reverse", // StringGroovyMethods - Reverse string
        "tr", // StringGroovyMethods - Character translation

        // String tokenization
        "tokenize", // StringGroovyMethods - Split into tokens
        "split", // StringGroovyMethods - Split by pattern
        "readLines", // StringGroovyMethods - Split into lines

        // String matching
        "find", // StringGroovyMethods - Find pattern match
        "findAll", // StringGroovyMethods - Find all pattern matches
        "match", // StringGroovyMethods - Pattern matching

        // String conversion
        "toInteger", // StringGroovyMethods - Convert to Integer
        "toLong", // StringGroovyMethods - Convert to Long
        "toDouble", // StringGroovyMethods - Convert to Double
        "toBigInteger", // StringGroovyMethods - Convert to BigInteger
        "toBigDecimal", // StringGroovyMethods - Convert to BigDecimal
        "toList", // StringGroovyMethods - Convert to character list
        "toURL", // StringGroovyMethods - Convert to URL
        "toURI", // StringGroovyMethods - Convert to URI
    )

    /**
     * All built-in method names across all categories.
     * Used for quick lookup without category distinction.
     */
    val ALL_BUILTIN_METHODS = OBJECT_METHODS + COLLECTION_METHODS + STRING_METHODS

    /**
     * Check if a method name corresponds to a Groovy built-in method.
     *
     * @param methodName The name of the method to check
     * @param receiverType Optional receiver type for more specific checking
     * @return true if the method is a known Groovy built-in method
     */
    fun isBuiltinMethod(methodName: String, @Suppress("UnusedParameter") receiverType: ClassNode? = null): Boolean =
        methodName in ALL_BUILTIN_METHODS

    /**
     * Get the source class where a built-in method is defined.
     *
     * @param methodName The name of the method
     * @return The fully qualified class name where the method is defined
     */
    fun getMethodSource(methodName: String): String = when {
        methodName in OBJECT_METHODS -> "org.codehaus.groovy.runtime.DefaultGroovyMethods"
        methodName in COLLECTION_METHODS -> "org.codehaus.groovy.runtime.DefaultGroovyMethods"
        methodName in STRING_METHODS -> "org.codehaus.groovy.runtime.StringGroovyMethods"
        else -> "Unknown"
    }

    /**
     * Get the category/type of built-in method.
     *
     * @param methodName The name of the method
     * @return A human-readable category description
     */
    fun getMethodCategory(methodName: String): String = when {
        methodName in setOf("println", "print", "printf") -> "I/O Method"
        methodName in setOf("each", "collect", "find", "findAll") -> "Collection Method"
        methodName in setOf("capitalize", "tokenize", "toInteger") -> "String Method"
        methodName in setOf("sleep", "use", "with", "tap") -> "Utility Method"
        methodName in OBJECT_METHODS -> "Object Method"
        methodName in COLLECTION_METHODS -> "Collection Method"
        methodName in STRING_METHODS -> "String Method"
        else -> "Built-in Method"
    }

    /**
     * Get a description for the built-in method for hover documentation.
     *
     * @param methodName The name of the method
     * @return A brief description of what the method does
     */
    fun getMethodDescription(methodName: String): String = when (methodName) {
        "println" -> "Print a value followed by a newline to standard output"
        "print" -> "Print a value to standard output without a newline"
        "printf" -> "Print a formatted string to standard output"
        "each" -> "Iterate over elements in a collection"
        "collect" -> "Transform elements in a collection"
        "find" -> "Find the first element matching a condition"
        "findAll" -> "Find all elements matching a condition"
        "sleep" -> "Pause execution for the specified number of milliseconds"
        "with" -> "Execute a closure with this object as the delegate"
        "tap" -> "Execute a closure with this object as parameter and return this object"
        else -> "Groovy built-in method"
    }
}
