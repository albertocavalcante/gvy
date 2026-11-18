// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.converters.toProtocolRange
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import com.github.albertocavalcante.gvy.semantics.sourceSimpleName
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind

private val logger = KotlinLogging.logger("SymbolLspExtensions")

/**
 * Converts a [Symbol] into a [SymbolInformation] for use in LSP responses. Ranges are protocol (UTF-16) ranges
 * when [text] holds the symbol's file; without it the Groovy columns are copied unchanged.
 */
@Suppress("DEPRECATION")
fun Symbol.toSymbolInformation(text: String? = null): SymbolInformation? {
    val range = protocolRange(text) ?: return null
    return SymbolInformation(
        displayName(),
        toSymbolKind(),
        Location(uri.toString(), range),
        containerName(),
    )
}

/**
 * Converts a [Symbol] into a [DocumentSymbol] without children: [buildDocumentOutline] nests them. The selection
 * range is the declared name when [text] holds the source, otherwise the whole declaration.
 */
internal fun Symbol.toDocumentSymbol(text: TextIndex? = null): DocumentSymbol? {
    val range = protocolRange(text?.text) ?: return null
    // A constructor's name is its class's, so the display name is also what the declaration spells.
    val selection = text?.let { firstNameOccurrence(it, range, displayName()) } ?: range
    val symbol = DocumentSymbol(displayName(), toSymbolKind(), range, selection)
    symbol.detail = detail()
    return symbol
}

private fun Symbol.protocolRange(text: String?): Range? {
    node.toProtocolRange(text)?.let { return it }
    val start = position.getOrNull()?.let { Position(it.line.value, it.column.value) } ?: return null
    return Range(start, start).toProtocolRange(text)
}

private fun Symbol.displayName(): String = when (this) {
    is Symbol.Method -> if (node.isConstructor) constructorName() else name
    is Symbol.Class -> node.sourceSimpleName()
    is Symbol.Field, is Symbol.Property, is Symbol.Variable, is Symbol.Import -> name
}

private fun Symbol.Method.constructorName(): String {
    val declaring = owner ?: node.declaringClass
    return declaring?.sourceSimpleName() ?: run {
        logger.warn { "Constructor symbol missing declaring class; using fallback name." }
        "constructor"
    }
}

private fun Symbol.toSymbolKind(): SymbolKind = when (this) {
    is Symbol.Class -> when {
        isInterface -> SymbolKind.Interface
        isEnum -> SymbolKind.Enum
        else -> SymbolKind.Class
    }
    is Symbol.Method -> if (node.isConstructor) SymbolKind.Constructor else SymbolKind.Method
    is Symbol.Field -> if (node.isEnum) SymbolKind.EnumMember else SymbolKind.Field
    is Symbol.Property -> SymbolKind.Property
    is Symbol.Variable -> SymbolKind.Variable
    is Symbol.Import -> SymbolKind.Module
}

private fun Symbol.containerName(): String? = when (this) {
    is Symbol.Method -> owner?.sourceSimpleName()
    is Symbol.Field -> owner?.sourceSimpleName()
    is Symbol.Property -> owner?.sourceSimpleName()
    is Symbol.Class -> node.outerClass?.sourceQualifiedName() ?: packageName
    is Symbol.Import -> packageName
    is Symbol.Variable -> null
}

private fun Symbol.detail(): String? = when (this) {
    is Symbol.Method -> displaySignature()
    is Symbol.Field -> type?.displayTypeName()
    is Symbol.Property -> type?.displayTypeName()
    is Symbol.Class -> node.sourceQualifiedName()
    is Symbol.Variable -> type?.displayTypeName()
    is Symbol.Import -> importedName
}

private fun Symbol.Method.displaySignature(): String = buildString {
    if (isStatic) append("static ")
    if (isAbstract) append("abstract ")
    append(visibility.keyword).append(" ")
    if (!node.isConstructor) append(returnType?.displayTypeName() ?: "def").append(" ")
    append(displayName()).append("(")
    append(parameters.joinToString(", ") { "${it.type.displayTypeName()} ${it.name}" })
    append(")")
}

/** The type as written in source: nested types without `$`, arrays as `T[]` (never the JVM's `T;`). */
internal fun ClassNode.displayTypeName(): String =
    if (isArray) componentType.displayTypeName() + "[]" else sourceSimpleName()

/** The package-qualified name with nesting written as `Outer.Inner`, as in source. */
internal fun ClassNode.sourceQualifiedName(): String {
    val outer = redirect().outerClass
    if (outer != null) return outer.sourceQualifiedName() + "." + sourceSimpleName()
    return packageName?.takeIf { it.isNotEmpty() }?.let { "$it.${sourceSimpleName()}" } ?: sourceSimpleName()
}
