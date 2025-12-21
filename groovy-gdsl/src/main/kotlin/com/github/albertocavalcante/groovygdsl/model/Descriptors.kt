package com.github.albertocavalcante.groovygdsl.model

/**
 * Represents a positional parameter in a method signature.
 *
 * Example from GDSL: `params: [script: 'java.lang.String']`
 */
data class ParameterDescriptor(
    /** Parameter name */
    val name: String,
    /** Fully qualified type name */
    val type: String,
    /** Optional documentation */
    val documentation: String? = null,
)

/**
 * Represents a named parameter in a method signature.
 *
 * Example from GDSL: `parameter(name: 'returnStdout', type: 'boolean')`
 */
data class NamedParameterDescriptor(
    /** Parameter name */
    val name: String,
    /** Fully qualified type name */
    val type: String,
    /** Whether this parameter is required */
    val required: Boolean = false,
    /** Default value as string, if any */
    val defaultValue: String? = null,
    /** Optional documentation */
    val documentation: String? = null,
)

/**
 * Represents a method contribution from GDSL.
 *
 * Captures method definitions from `method(name: 'echo', type: 'void', ...)` calls.
 */
data class MethodDescriptor(
    /** Method name (e.g., "echo", "sh") */
    val name: String,
    /** Return type (fully qualified or simple) */
    val returnType: String,
    /** Positional parameters from `params: [...]` */
    val parameters: List<ParameterDescriptor> = emptyList(),
    /** Named parameters from `namedParams: [...]` */
    val namedParameters: List<NamedParameterDescriptor> = emptyList(),
    /** Documentation string from `doc: '...'` */
    val documentation: String? = null,
    /**
     * Raw `context(...)` definition map from the owning contributor.
     *
     * This is captured verbatim to enable downstream tooling (e.g. Jenkins metadata extraction)
     * to make deterministic decisions based on scope/type without re-parsing the original GDSL text.
     */
    val context: Map<String, Any> = emptyMap(),
    /**
     * Optional enclosing call hint captured from `enclosingCall('...')` usage.
     *
     * This is used by some GDSL scripts (e.g. Jenkins) to scope contributions to blocks like `node { ... }`.
     */
    val enclosingCall: String? = null,
)

/**
 * Represents a property contribution from GDSL.
 *
 * Captures property definitions from `property(name: 'env', type: '...')` calls.
 */
data class PropertyDescriptor(
    /** Property name (e.g., "env", "params", "currentBuild") */
    val name: String,
    /** Property type (fully qualified or simple) */
    val type: String,
    /** Optional documentation */
    val documentation: String? = null,
    /** Raw `context(...)` definition map from the owning contributor. */
    val context: Map<String, Any> = emptyMap(),
    /** Optional enclosing call hint captured from `enclosingCall('...')` usage. */
    val enclosingCall: String? = null,
)

/**
 * Represents context filters for GDSL contributions.
 *
 * GDSL contributions are scoped to specific contexts (script scope, closure scope, etc.).
 * This sealed class hierarchy enables exhaustive when expressions.
 */
sealed class ContextFilter {
    /**
     * Script-level scope - contributions available at the top level of a script.
     *
     * Example: `context(scope: scriptScope(), filetypes: ['groovy'])`
     */
    data class ScriptScope(
        /** File extensions this scope applies to */
        val filetypes: List<String> = emptyList(),
    ) : ContextFilter()

    /**
     * Closure scope - contributions available inside closures.
     *
     * Example: `context(scope: closureScope())` with `enclosingCall('node')`
     */
    data class ClosureScope(
        /** If set, only applies when inside this method call (e.g., "node") */
        val enclosingCall: String? = null,
    ) : ContextFilter()

    /**
     * Class scope - contributions available on specific types.
     *
     * Example: `context(ctype: 'java.lang.String')`
     */
    data class ClassScope(
        /** The fully qualified class name this scope applies to */
        val ctype: String,
    ) : ContextFilter()
}

/**
 * Result of parsing a GDSL script.
 *
 * Contains all captured contributions (methods, properties) and success status.
 * Lists are defensively copied to ensure immutability.
 */
class GdslParseResult private constructor(
    methods: List<MethodDescriptor>,
    properties: List<PropertyDescriptor>,
    /** Whether parsing succeeded */
    val success: Boolean,
    /** Error message if parsing failed */
    val error: String?,
) {
    /** All method contributions (immutable) */
    val methods: List<MethodDescriptor> = methods.toList()

    /** All property contributions (immutable) */
    val properties: List<PropertyDescriptor> = properties.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GdslParseResult) return false
        return methods == other.methods &&
            properties == other.properties &&
            success == other.success &&
            error == other.error
    }

    override fun hashCode(): Int {
        var result = methods.hashCode()
        result = 31 * result + properties.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "GdslParseResult(methods=$methods, properties=$properties, success=$success, error=$error)"

    companion object {
        /** Creates a successful result with the given methods and properties */
        operator fun invoke(
            methods: List<MethodDescriptor>,
            properties: List<PropertyDescriptor>,
            success: Boolean = true,
            error: String? = null,
        ): GdslParseResult = GdslParseResult(methods, properties, success, error)

        /** Creates an empty successful result */
        fun empty(): GdslParseResult = GdslParseResult(
            methods = emptyList(),
            properties = emptyList(),
            success = true,
            error = null,
        )

        /** Creates a failed result with an error message */
        fun error(message: String): GdslParseResult = GdslParseResult(
            methods = emptyList(),
            properties = emptyList(),
            success = false,
            error = message,
        )
    }
}
