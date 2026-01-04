package com.github.albertocavalcante.gvy.semantics

/**
 * Unified type representation for all GVY semantic analysis.
 *
 * Design decisions:
 * - Sealed interface for exhaustive when() matching
 * - [Known] for fully resolved reference types
 * - [Dynamic] for def/Object in dynamic contexts (still useful for IDE)
 * - [Unknown] for types that couldn't be inferred (includes reason)
 * - [Union] for multi-branch expressions like ternary
 *
 * Example usage:
 * ```kotlin
 * when (type) {
 *     is SemanticType.Known -> println("Type: ${type.fqn}")
 *     is SemanticType.Primitive -> println("Primitive: ${type.kind}")
 *     is SemanticType.Dynamic -> println("Dynamic type")
 *     is SemanticType.Unknown -> println("Unknown: ${type.reason}")
 *     // ... exhaustive
 * }
 * ```
 */
sealed interface SemanticType {

    /**
     * Fully resolved reference type with FQN.
     *
     * @property fqn Fully qualified name, e.g., "java.util.List"
     * @property typeArgs Generic type arguments, e.g., [Known("java.lang.String")]
     */
    data class Known(val fqn: String, val typeArgs: List<SemanticType> = emptyList()) : SemanticType {
        override fun toString(): String = if (typeArgs.isEmpty()) {
            fqn
        } else {
            "$fqn<${typeArgs.joinToString(", ")}>"
        }
    }

    /**
     * Java/Groovy primitive type.
     *
     * @property kind The specific primitive kind (e.g., INT, BOOLEAN)
     */
    data class Primitive(val kind: PrimitiveKind) : SemanticType {
        override fun toString(): String = kind.name.lowercase()
    }

    /**
     * Dynamic type (def, Object in dynamic context).
     * Still valid for IDE features - just less specific.
     *
     * @property hint Optional hint about what the type might be
     */
    data class Dynamic(val hint: String? = null) : SemanticType {
        override fun toString(): String = hint?.let { "dynamic($it)" } ?: "dynamic"
    }

    /**
     * Could not infer type. Includes reason for debugging.
     * IDE features should degrade gracefully for Unknown types.
     *
     * @property reason Why the type couldn't be inferred
     */
    data class Unknown(val reason: String) : SemanticType {
        override fun toString(): String = "unknown($reason)"
    }

    /**
     * Union type for multi-branch expressions.
     *
     * Example: `if (cond) "string" else 42` → Union(Known(String), Primitive(INT))
     *
     * @property types The set of possible types in this union. Must contain at least 2 types.
     */
    data class Union(val types: Set<SemanticType>) : SemanticType {
        init {
            require(types.size >= 2) { "Union requires at least 2 types" }
        }

        override fun toString(): String = types.joinToString(" | ")
    }

    /**
     * Null literal type.
     * Represents the type of the `null` literal.
     */
    data object Null : SemanticType {
        override fun toString(): String = "null"
    }

    /**
     * Array type.
     *
     * @property componentType Type of array elements
     */
    data class Array(val componentType: SemanticType) : SemanticType {
        override fun toString(): String = "$componentType[]"
    }
}
