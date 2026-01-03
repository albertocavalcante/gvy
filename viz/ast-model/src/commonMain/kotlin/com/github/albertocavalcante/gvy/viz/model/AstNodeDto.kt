package com.github.albertocavalcante.gvy.viz.model

import kotlinx.serialization.Serializable

/**
 * Sealed interface representing an AST node in a platform-agnostic, serializable format.
 *
 * This interface provides a common abstraction for AST nodes from different parsers
 * (Core parser and Native parser), allowing them to be visualized and exported
 * in a consistent manner.
 *
 * @property id Unique identifier for this node (used for tree navigation).
 * @property type The type of this node (e.g., "ClassDeclaration", "MethodNode").
 * @property range The source location of this node, or null if not available.
 * @property children Child nodes in the AST.
 * @property properties Node-specific properties as key-value pairs (e.g., "name" -> "MyClass").
 */
@Serializable
sealed interface AstNodeDto {
    val id: String
    val type: String
    val range: RangeDto?
    val children: List<AstNodeDto>
    val properties: Map<String, String>
}

/**
 * AST node from the Core parser (JavaParser-like AST).
 *
 * Core parser provides a clean, simplified AST without symbol tables or type information.
 *
 * @property id Unique identifier for this node.
 * @property type The type of this node (e.g., "ClassDeclaration", "MethodDeclaration").
 * @property range The source location of this node, or null if not available.
 * @property children Child nodes in the AST.
 * @property properties Node-specific properties as key-value pairs.
 */
@Serializable
data class CoreAstNodeDto(
    override val id: String,
    override val type: String,
    override val range: RangeDto?,
    override val children: List<AstNodeDto>,
    override val properties: Map<String, String>,
) : AstNodeDto

/**
 * AST node from the Native parser (Groovy compiler's AST).
 *
 * Native parser provides richer information including symbol tables and type resolution.
 *
 * @property id Unique identifier for this node.
 * @property type The type of this node (e.g., "ClassNode", "MethodNode").
 * @property range The source location of this node, or null if not available.
 * @property children Child nodes in the AST.
 * @property properties Node-specific properties as key-value pairs.
 * @property symbolInfo Symbol table information (if available).
 * @property typeInfo Type resolution information (if available).
 */
@Serializable
data class NativeAstNodeDto(
    override val id: String,
    override val type: String,
    override val range: RangeDto?,
    override val children: List<AstNodeDto>,
    override val properties: Map<String, String>,
    val symbolInfo: SymbolInfoDto? = null,
    val typeInfo: TypeInfoDto? = null,
) : AstNodeDto
