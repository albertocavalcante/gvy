package com.github.albertocavalcante.groovylsp.sources

import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(GdkMethodIndex::class.java)

    // Thread-safe map: method signature -> parameter names
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
        if (parameterNames.isEmpty()) {
            logger.debug("Skipping method with no parameters: {}.{}", className, methodName)
            return
        }

        val signature = buildSignature(className, methodName, parameterTypes)
        index[signature] = parameterNames
        logger.trace("Indexed: {} -> {}", signature, parameterNames)
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
        logger.info("Cleared GDK method index")
    }
}
