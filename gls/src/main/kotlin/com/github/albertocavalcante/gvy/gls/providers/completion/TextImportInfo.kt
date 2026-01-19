package com.github.albertocavalcante.gvy.gls.providers.completion

/**
 * Result of parsing import information from source text.
 */
internal data class TextImportInfo(
    val packageName: String?,
    val explicitImports: Set<String>,
    val starImports: Set<String>,
)
