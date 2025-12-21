package com.github.albertocavalcante.groovygdsl

import com.github.albertocavalcante.groovygdsl.model.MethodDescriptor
import com.github.albertocavalcante.groovygdsl.model.NamedParameterDescriptor
import com.github.albertocavalcante.groovygdsl.model.ParameterDescriptor
import com.github.albertocavalcante.groovygdsl.model.PropertyDescriptor

/**
 * Delegate for the contributor closure in GDSL scripts.
 *
 * Captures method() and property() calls as descriptors for later use
 * in completion and type inference.
 *
 * Example GDSL usage:
 * ```
 * contributor([context]) {
 *     method(name: 'echo', type: 'void', params: [message: 'String'], doc: 'Print message')
 *     property(name: 'env', type: 'EnvActionImpl')
 * }
 * ```
 */
class GdslContributor(val context: GdslContext) {

    private val _methods = mutableListOf<MethodDescriptor>()
    private val _properties = mutableListOf<PropertyDescriptor>()

    /** All captured method definitions (immutable snapshot) */
    val methods: List<MethodDescriptor> get() = _methods.toList()

    /** All captured property definitions (immutable snapshot) */
    val properties: List<PropertyDescriptor> get() = _properties.toList()

    /**
     * Captures a method definition.
     *
     * @param args Map containing: name (required), type, params, namedParams, doc
     */
    fun method(args: Map<String, Any?>) {
        val name = args["name"]?.toString() ?: return
        val returnType = stringifyType(args["type"])
        val documentation = args["doc"]?.toString()

        val parameters = parsePositionalParams(args["params"])
        val namedParameters = parseNamedParams(args["namedParams"])

        _methods.add(
            MethodDescriptor(
                name = name,
                returnType = returnType,
                parameters = parameters,
                namedParameters = namedParameters,
                documentation = documentation,
            ),
        )
    }

    /**
     * Captures a property definition.
     *
     * @param args Map containing: name (required), type, doc
     */
    fun property(args: Map<String, Any?>) {
        val name = args["name"]?.toString() ?: return
        val type = stringifyType(args["type"])
        val documentation = args["doc"]?.toString()

        _properties.add(
            PropertyDescriptor(
                name = name,
                type = type,
                documentation = documentation,
            ),
        )
    }

    /**
     * Creates a named parameter descriptor for use in namedParams list.
     *
     * @param args Map containing: name, type, doc
     * @return NamedParameterDescriptor for use in method definitions
     */
    fun parameter(args: Map<String, Any?>): NamedParameterDescriptor = NamedParameterDescriptor(
        name = args["name"]?.toString() ?: "",
        type = stringifyType(args["type"]),
        documentation = args["doc"]?.toString(),
    )

    /**
     * Specifies that the closure delegates to a type.
     *
     * @param type The type to delegate to
     */
    fun delegatesTo(type: Any) {
        // TODO: Capture delegation for closure inference
    }

    /**
     * Checks if we're inside a specific enclosing call.
     *
     * This is used in GDSL to filter contributions based on context,
     * e.g., methods only available inside `node { ... }` blocks.
     *
     * NOTE: This is a stub that always returns true for now.
     * At execution time during parsing, we capture all contributions.
     * Context filtering happens later at completion time.
     *
     * @param methodName The method name to check for enclosing call
     * @return Always true during GDSL parsing (contributions are always captured)
     */
    fun enclosingCall(methodName: String): Boolean {
        // NOTE: During GDSL parsing, we capture all contributions regardless
        // of context. The enclosingCall condition is evaluated at completion
        // time to filter which contributions apply.
        // TODO: Implement proper context tracking for completion filtering
        return true
    }

    /**
     * Utility method to find a class by name.
     *
     * @param className The class name to find
     * @return The class name (pass-through for now)
     */
    fun findClass(className: String): String = className

    /**
     * Converts various type representations to a string.
     */
    private fun stringifyType(type: Any?): String = when (type) {
        null -> DEFAULT_TYPE
        is Class<*> -> type.name
        is String -> type
        else -> type.toString()
    }

    /**
     * Parses positional parameters from a params map.
     *
     * Example: `params: [script: 'String', body: Closure]`
     */
    private fun parsePositionalParams(params: Any?): List<ParameterDescriptor> {
        val map = params as? Map<*, *> ?: return emptyList()
        return map.entries.mapNotNull { (key, value) ->
            val name = key?.toString() ?: return@mapNotNull null
            val type = stringifyType(value)
            ParameterDescriptor(name = name, type = type)
        }
    }

    /**
     * Parses named parameters from a namedParams list.
     *
     * Example: `namedParams: [parameter(name: 'script', type: 'String'), ...]`
     */
    private fun parseNamedParams(namedParams: Any?): List<NamedParameterDescriptor> {
        val list = namedParams as? List<*> ?: return emptyList()
        return list.filterIsInstance<NamedParameterDescriptor>()
    }

    companion object {
        private const val DEFAULT_TYPE = "java.lang.Object"
    }
}
