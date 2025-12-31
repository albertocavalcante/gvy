package com.github.albertocavalcante.groovylsp.engine.config

/**
 * Sealed interface representing parser engine types.
 *
 * Using sealed interface enables exhaustive `when` expressions and compile-time safety.
 * When adding a new engine type, the compiler will ensure all `when` expressions are updated.
 */
sealed interface EngineType {
    /** Unique identifier for this engine type. */
    val id: String

    /**
     * Native engine using Groovy compiler's AST.
     * This is the current (default) implementation.
     */
    data object Native : EngineType {
        override val id: String = "native"
    }

    /**
     * Core engine using groovyparser-core's custom AST.
     * This is the future implementation with cleaner AST and better type inference.
     */
    data object Core : EngineType {
        override val id: String = "core"
    }

    /**
     * OpenRewrite engine using OpenRewrite's LST (Lossless Semantic Trees).
     * Future implementation for refactoring-focused analysis.
     */
    data object OpenRewrite : EngineType {
        override val id: String = "openrewrite"
    }

    companion object {
        /**
         * Parse engine type from string, defaulting to [Native].
         *
         * @param value Engine type string (case-insensitive), e.g. "native", "core"
         * @return Corresponding [EngineType], or [Native] for unknown/null values
         */
        fun fromString(value: String?): EngineType = when (value?.lowercase()) {
            "core" -> Core
            "openrewrite" -> OpenRewrite
            else -> Native // Default to Native for null, "native", or unknown values
        }

        /** All available engine types. */
        val entries: List<EngineType> = listOf(Native, Core, OpenRewrite)
    }
}

/**
 * Configuration for engine selection and capabilities.
 *
 * @property type The parser engine type to use
 * @property features Feature flags for engine-specific behavior
 */
data class EngineConfiguration(
    val type: EngineType = EngineType.Native,
    val features: EngineFeatures = EngineFeatures(),
)

/**
 * Feature flags for engine-specific behavior.
 *
 * @property typeInference Enable type inference (default: true)
 * @property flowAnalysis Enable flow analysis for null-safety (default: false, experimental)
 */
data class EngineFeatures(val typeInference: Boolean = true, val flowAnalysis: Boolean = false)
