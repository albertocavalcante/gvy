package com.github.albertocavalcante.gvy.viz.model

import kotlinx.serialization.Serializable

/**
 * Represents symbol table information for an AST node (Native parser only).
 *
 * @property kind The kind of symbol (e.g., "METHOD", "FIELD", "VARIABLE", "CLASS").
 * @property scope The scope where the symbol is defined (e.g., "CLASS", "METHOD", "BLOCK").
 * @property visibility The visibility modifier (e.g., "PUBLIC", "PRIVATE", "PROTECTED").
 */
@Serializable
data class SymbolInfoDto(val kind: String, val scope: String, val visibility: String)
