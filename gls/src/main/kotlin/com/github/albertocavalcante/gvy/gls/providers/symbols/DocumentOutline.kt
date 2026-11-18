// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.isScriptSafe
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import java.util.IdentityHashMap

private val positionOrder = compareBy<DocumentSymbol>({ it.range.start.line }, { it.range.start.character })

/**
 * Builds the outline of one file: top-level types and script members, each with its fields, properties,
 * constructors, methods and nested types as children, in source order.
 *
 * Script classes are not containers: Groovy wraps a script's own methods in a generated class, and the outline
 * shows them at top level like the source does. Positions are protocol (UTF-16) positions over [text].
 */
internal fun buildDocumentOutline(symbols: List<Symbol>, text: TextIndex?): List<DocumentSymbol> {
    val declared = symbols.filter { it.isSourceDeclaration() && !(it is Symbol.Class && it.node.isScript) }
    val outline = declared.mapNotNull { symbol -> symbol.toDocumentSymbol(text)?.let { symbol to it } }
    val byClass = IdentityHashMap<ClassNode, DocumentSymbol>()
    outline.forEach { (symbol, entry) -> if (symbol is Symbol.Class) byClass[symbol.node] = entry }

    val roots = mutableListOf<DocumentSymbol>()
    val children = IdentityHashMap<DocumentSymbol, MutableList<DocumentSymbol>>()
    outline.forEach { (symbol, entry) ->
        val parent = symbol.containingClass()?.let { byClass[it] }
        if (parent == null) roots += entry else children.getOrPut(parent) { mutableListOf() } += entry
    }
    outline.forEach { (_, entry) -> entry.children = children[entry].orEmpty().sortedWith(positionOrder) }
    return roots.sortedWith(positionOrder)
}

/** The outline as a flat list for clients without hierarchical support; each entry names its parent. */
@Suppress("DEPRECATION")
internal fun flattenOutline(
    uri: String,
    outline: List<DocumentSymbol>,
    container: String? = null,
): List<SymbolInformation> = outline.flatMap { entry ->
    val flat = SymbolInformation(entry.name, entry.kind, Location(uri, entry.range), container)
    listOf(flat) + flattenOutline(uri, entry.children.orEmpty(), entry.name)
}

private fun Symbol.containingClass(): ClassNode? = when (this) {
    is Symbol.Class -> node.outerClass
    is Symbol.Method -> owner
    is Symbol.Field -> owner
    is Symbol.Property -> owner
    is Symbol.Variable, is Symbol.Import -> null
}
