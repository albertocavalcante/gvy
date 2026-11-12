// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.engine.config

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
     * Reuses the compiled document and its project classpath for hover.
     */
    data object Native : EngineType {
        override val id: String = "native"
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
         * The legacy `core` setting aliases Native through 0.5.5 and is removed in 0.5.6.
         * Parsing stays pure; configuration intake owns the per-server migration warning.
         *
         * @return Corresponding [EngineType], or [Native] for unknown/null values
         */
        fun fromString(value: String?): EngineType = when (value?.lowercase()) {
            "native", "core" -> Native
            "openrewrite" -> OpenRewrite
            else -> Native
        }

        /** All available engine types. */
        val entries: List<EngineType> = listOf(Native, OpenRewrite)
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
