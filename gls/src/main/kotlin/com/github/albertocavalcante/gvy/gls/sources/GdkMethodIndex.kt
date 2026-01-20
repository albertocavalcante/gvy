package com.github.albertocavalcante.gvy.gls.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe index mapping GDK method signatures to their parameter names.
 *
 * This index is populated by parsing Groovy source files (e.g., DefaultGroovyMethods.java)
 * to extract real parameter names instead of relying on reflection which yields synthetic names.
 *
 * The key format is: "ClassName.methodName(ParamType1,ParamType2,...)"
 * Example: "DefaultGroovyMethods.each(Closure)" -> ["closure"]
 */
class GdkMethodIndex {
    private val logger = KotlinLogging.logger {}

    // Cache is bounded by GDK_CLASSES size (~8 classes) * methods per class (~100), so no LRU eviction needed
    private val index = ConcurrentHashMap<String, List<String>>()

    /**
     * Add a method signature to the index.
     *
     * @param className Simple class name (e.g., "DefaultGroovyMethods")
     * @param methodName Method name (e.g., "each")
     * @param parameterTypes Parameter types as simple names (e.g., ["Closure"])
     * @param parameterNames Parameter names from source (e.g., ["closure"])
     */
    fun addMethod(className: String, methodName: String, parameterTypes: List<String>, parameterNames: List<String>) {
        require(parameterTypes.size == parameterNames.size) {
            "Parameter types and names must have same size for $className.$methodName"
        }
        if (parameterNames.isEmpty()) {
            logger.debug { "Skipping method with no parameters: $className.$methodName" }
            return
        }

        val signature = buildSignature(className, methodName, parameterTypes)
        index[signature] = parameterNames
        logger.trace { "Indexed: $signature -> $parameterNames" }
    }

    /**
     * Get parameter names for a method signature.
     *
     * @param className Simple class name (e.g., "DefaultGroovyMethods")
     * @param methodName Method name (e.g., "each")
     * @param parameterTypes Parameter types as simple names (e.g., ["Closure"])
     * @return List of parameter names, or null if not found
     */
    fun getParameterNames(className: String, methodName: String, parameterTypes: List<String>): List<String>? {
        val signature = buildSignature(className, methodName, parameterTypes)
        return index[signature]
    }

    /**
     * Build a method signature key.
     *
     * Format: "ClassName.methodName(Type1,Type2,...)"
     * Example: "DefaultGroovyMethods.each(Closure)"
     */
    private fun buildSignature(className: String, methodName: String, parameterTypes: List<String>): String =
        "$className.$methodName(${parameterTypes.joinToString(",")})"

    /**
     * Get statistics about the index.
     */
    fun getStatistics(): Map<String, Any> = mapOf(
        "indexedMethods" to index.size,
        "sampleKeys" to index.keys.take(5).toList(),
    )

    /**
     * Clear the index.
     */
    fun clear() {
        index.clear()
        logger.info { "Cleared GDK method index" }
    }
}
