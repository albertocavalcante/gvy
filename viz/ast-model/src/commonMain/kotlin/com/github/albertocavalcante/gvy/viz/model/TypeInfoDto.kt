package com.github.albertocavalcante.gvy.viz.model

import kotlinx.serialization.Serializable

/**
 * Represents type information for an AST node (Native parser only).
 *
 * @property resolvedType The fully qualified type name (e.g., "java.lang.String", "java.math.BigDecimal").
 * @property isInferred Whether the type was inferred or explicitly declared.
 * @property typeParameters Type parameters for generic types (e.g., ["String", "Integer"] for Map<String, Integer>).
 */
@Serializable
data class TypeInfoDto(val resolvedType: String, val isInferred: Boolean, val typeParameters: List<String>)
