// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

/** Exact syntactic identity; changing an overload, call text or occurrence count requires review. */
internal data class CacheAccessKey(
    val packageName: String,
    val file: String,
    val owner: String,
    val function: String,
    val parameterTypes: List<String>,
    val expression: String,
)

internal data class CacheAccessException(
    val key: CacheAccessKey,
    val count: Int,
    val rationale: String,
    val owner: String,
    val removalGate: String,
)

internal fun List<CacheAccessException>.allows(key: CacheAccessKey, occurrence: Int): Boolean =
    singleOrNull { it.key == key }?.let { occurrence > 0 && occurrence <= it.count } ?: false

// Public compiler adapters remain source-compatible; each entry is confined to one exact forwarding call.
// Owner: #2461. Remove each entry when the corresponding public compatibility accessor is retired.
internal val CACHE_ACCESS_EXCEPTIONS: List<CacheAccessException> = listOf(
    "getAst", "getParseResult", "getDiagnostics", "getTokenIndex", "getAstModel", "getSymbolTable",
).map { name ->
    CacheAccessException(
        CacheAccessKey(
            "com.github.albertocavalcante.gvy.gls.compilation", "GroovyCompilationService.kt",
            "GroovyCompilationService", name, listOf("URI"), "parseAccessor.$name(uri)",
        ),
        count = 1,
        rationale = "Public compatibility accessor forwards to its compiler-owned accessor.",
        owner = "#2461",
        removalGate = "Retire this public compatibility accessor after its consumers migrate.",
    )
}
