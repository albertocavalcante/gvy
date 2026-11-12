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
     * Core engine using groovyparser-core's custom AST.
     * Available as an explicit alternative to the default compiler-backed engine.
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
            "native" -> Native
            "core" -> Core
            "openrewrite" -> OpenRewrite
            else -> Native
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
