package com.github.albertocavalcante.groovygdsl

import com.github.albertocavalcante.groovygdsl.model.MethodDescriptor
import com.github.albertocavalcante.groovygdsl.model.PropertyDescriptor
import groovy.lang.Closure
import groovy.lang.Script

/**
 * Base script class for GDSL files.
 *
 * This class defines the DSL available within a .gdsl file, including:
 * - context() for defining contribution contexts
 * - contributor() for defining contributions (methods, properties)
 * - Scope helpers (scriptScope, closureScope, classScope)
 *
 * All contributions are tracked and can be retrieved after script execution.
 */
abstract class GdslScript : Script() {

    private val _contributors = mutableListOf<GdslContributor>()

    /** All contributors registered in this script */
    val contributors: List<GdslContributor> get() = _contributors.toList()

    /** All methods from all contributors */
    val allMethods: List<MethodDescriptor>
        get() = _contributors.flatMap { it.methods }

    /** All properties from all contributors */
    val allProperties: List<PropertyDescriptor>
        get() = _contributors.flatMap { it.properties }

    /**
     * Defines a context for GDSL contributions (no arguments version).
     *
     * @return A GdslContext with empty definition.
     */
    fun context(): GdslContext = GdslContext(emptyMap())

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
    fun contributor(contexts: Any?, closure: Closure<*>) {
        val contextList = when (contexts) {
            is List<*> -> contexts.filterIsInstance<GdslContext>()
            is GdslContext -> listOf(contexts)
            null -> listOf(GdslContext(emptyMap()))
            else -> emptyList()
        }

        contextList.forEach { ctx ->
            val contributor = GdslContributor(ctx)
            closure.delegate = contributor
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
            _contributors.add(contributor)
        }
    }

    // Scope helper methods

    /**
     * Creates a script scope definition (no arguments).
     */
    fun scriptScope(): Map<String, Any> = mapOf("type" to "script")

    /**
     * Creates a script scope definition.
     *
     * Example: `context(scope: scriptScope())`
     */
    fun scriptScope(args: Map<String, Any>): Map<String, Any> = args + mapOf("type" to "script")

    /**
     * Creates a closure scope definition (no arguments).
     */
    fun closureScope(): Map<String, Any> = mapOf("type" to "closure")

    /**
     * Creates a closure scope definition.
     *
     * Example: `context(scope: closureScope())`
     */
    fun closureScope(args: Map<String, Any>): Map<String, Any> = args + mapOf("type" to "closure")

    /**
     * Creates a class scope definition (no arguments).
     */
    fun classScope(): Map<String, Any> = mapOf("type" to "class")

    /**
     * Creates a class scope definition.
     *
     * Example: `context(scope: classScope())`
     */
    fun classScope(args: Map<String, Any>): Map<String, Any> = args + mapOf("type" to "class")

    /**
     * Creates an annotated scope definition (no arguments).
     */
    fun annotatedScope(): Map<String, Any> = mapOf("type" to "annotated")

    /**
     * Creates an annotated scope definition.
     *
     * Example: `context(scope: annotatedScope(annotationName: 'javax.inject.Inject'))`
     */
    fun annotatedScope(args: Map<String, Any>): Map<String, Any> = args + mapOf("type" to "annotated")
}
