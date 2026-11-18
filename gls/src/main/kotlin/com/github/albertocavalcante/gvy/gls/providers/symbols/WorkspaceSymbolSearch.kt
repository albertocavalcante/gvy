// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import com.github.albertocavalcante.gvy.semantics.sourceSimpleName
import org.eclipse.lsp4j.SymbolInformation
import java.net.URI

/**
 * The first [limit] declarations whose source name contains [query] (case-insensitive; a blank query matches
 * all), in the order of [indices]. Only what the user wrote is listed, under the name they wrote: nested
 * classes are `Inner` with their outer class as container, not `Outer$Inner`; imports, parameters, anonymous
 * classes and generated members are left out. [sourceOf] supplies a file's text so ranges are UTF-16; a file it
 * cannot read keeps its Groovy columns. It is asked at most once per file.
 */
internal fun searchWorkspaceSymbols(
    indices: Map<URI, SymbolIndex>,
    query: String,
    limit: Int,
    sourceOf: (URI) -> String?,
): List<SymbolInformation> {
    val sources = HashMap<URI, String?>()
    val needle = if (query.isBlank()) "" else query
    return indices.asSequence()
        .flatMap { (uri, index) -> index.getSymbols(uri).asSequence() }
        .filter { it.isSourceDeclaration() && it.searchName().contains(needle, ignoreCase = true) }
        .mapNotNull { symbol -> symbol.toSymbolInformation(sources.getOrPut(symbol.uri) { sourceOf(symbol.uri) }) }
        .take(limit)
        .toList()
}

/** What a user types to find this symbol: a class by its source name, anything else by its own name. */
private fun Symbol.searchName(): String = if (this is Symbol.Class) node.sourceSimpleName() else name
