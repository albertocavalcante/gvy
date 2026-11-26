// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.detekt

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

private const val COMPILER_FILE =
    "gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/compilation/GroovyCompilationService.kt"

/** Detekt uses the normalized physical path as the PSI file name. String snippets have no project identity. */
internal fun compilerForwarderFile(file: KtFile): String? {
    val path = Path.of(file.name).normalize()
    if (!path.isAbsolute || !path.endsWith(COMPILER_FILE)) return null
    val root = generateSequence(path.parent) { it.parent }.firstOrNull {
        Files.isRegularFile(it.resolve("settings.gradle.kts"))
    } ?: return null
    return root.relativize(path).invariantSeparatorsPathString.takeIf { it == COMPILER_FILE }
}

/** Only the complete expression body of a direct member of the top-level compiler class can consume a permit. */
internal fun forwarderKey(file: String, expression: KtExpression, receivers: CacheReceiverLookup): CacheAccessKey? {
    val function = expression.parent as? KtNamedFunction ?: return null
    val owner = (function.parent as? KtClassBody)?.parent as? KtClass ?: return null
    val source = owner.parent as? KtFile ?: return null
    val parameter = function.valueParameters.singleOrNull() ?: return null
    if (function.bodyExpression != expression || function.receiverTypeReference != null) return null
    if (function.typeParameters.isNotEmpty() || function.modifierList != null || function.typeReference != null) {
        return null
    }
    if (parameter.text != "uri: URI" || receivers.declaredType(parameter.typeReference) != "java.net.URI") return null
    return CacheAccessKey(
        source.packageFqName.asString(), file, owner.name.orEmpty(), function.name.orEmpty(),
        listOf(parameter.typeReference?.text.orEmpty()), expression.text,
    )
}

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
            "$GLS_PACKAGE.compilation", COMPILER_FILE,
            "GroovyCompilationService", name, listOf("URI"), "parseAccessor.$name(uri)",
        ),
        count = 1,
        rationale = "Public compatibility accessor forwards to its compiler-owned accessor.",
        owner = "#2461",
        removalGate = "Retire this public compatibility accessor after its consumers migrate.",
    )
}
