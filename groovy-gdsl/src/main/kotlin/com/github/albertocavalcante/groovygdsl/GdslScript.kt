package com.github.albertocavalcante.groovygdsl

import groovy.lang.Closure
import groovy.lang.Script

/**
 * Base script class for GDSL files.
 * This class defines the DSL available within a .gdsl file.
 */
abstract class GdslScript : Script() {

    /**
     * Defines a context for GDSL contributions.
     *
     * @param args Named arguments defining the context (e.g. scope, filetypes, ctype).
     * @return A GdslContext object representing the defined context.
     */
    fun context(args: Map<String, Any>): GdslContext = GdslContext(args)

    /**
     * Defines a contribution to one or more contexts.
     *
     * @param contexts A list of contexts (or a single context) to contribute to.
     * @param closure The closure defining the contributions (methods, properties, etc.).
     */
    fun contributor(contexts: Any, closure: Closure<Any>) {
        val contextList = if (contexts is List<*>) contexts else listOf(contexts)

        contextList.forEach { ctx ->
            if (ctx is GdslContext) {
                // In a real implementation, we would register this contribution
                // For now, we just execute it to verify the structure
                closure.delegate = GdslContributor(ctx)
                closure.resolveStrategy = Closure.DELEGATE_FIRST
                closure.call()
            }
        }
    }

    // Helper methods often used in GDSL

    fun scriptScope(args: Map<String, Any> = emptyMap()): Map<String, Any> = args + mapOf("type" to "script")

    fun closureScope(args: Map<String, Any> = emptyMap()): Map<String, Any> = args + mapOf("type" to "closure")

    fun classScope(args: Map<String, Any> = emptyMap()): Map<String, Any> = args + mapOf("type" to "class")
}
