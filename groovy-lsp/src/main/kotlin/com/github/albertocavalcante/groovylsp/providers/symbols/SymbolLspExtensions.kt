package com.github.albertocavalcante.groovylsp.providers.symbols

import com.github.albertocavalcante.groovylsp.converters.toLspRange
import com.github.albertocavalcante.groovyparser.ast.safeRange
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("SymbolLspExtensions")

/**
 * Converts a [Symbol] into a [SymbolInformation] for use in LSP responses.
 */
@Suppress("DEPRECATION")
fun Symbol.toSymbolInformation(): SymbolInformation? {
    val range = toLspRange() ?: return null
    return SymbolInformation(
        displayName(),
        toSymbolKind(),
        Location(uri.toString(), range),
        containerName(),
    )
}

/**
 * Converts a [Symbol] into a [DocumentSymbol]. Currently we emit flat symbols (no children).
 */
fun Symbol.toDocumentSymbol(): DocumentSymbol? {
    val range = toLspRange() ?: return null
    val symbol = DocumentSymbol(displayName(), toSymbolKind(), range, range)
    symbol.detail = detail()
    return symbol
}

private fun Symbol.displayName(): String = when (this) {
    is Symbol.Method -> if (node.isConstructor) {
        owner?.nameWithoutPackage
            ?: owner?.name
            ?: node.declaringClass?.nameWithoutPackage
            ?: node.declaringClass?.name
            ?: run {
                logger.warn("Constructor symbol missing declaring class; using fallback name.")
                "constructor"
            }
    } else {
        name
    }

    is Symbol.Class, is Symbol.Field, is Symbol.Property, is Symbol.Variable, is Symbol.Import -> name
}

private fun Symbol.toSymbolKind(): SymbolKind = when (this) {
    is Symbol.Class -> SymbolKind.Class
    is Symbol.Method -> if (node.isConstructor) SymbolKind.Constructor else SymbolKind.Method
    is Symbol.Field -> SymbolKind.Field
    is Symbol.Property -> SymbolKind.Property
    is Symbol.Variable -> SymbolKind.Variable
    is Symbol.Import -> SymbolKind.Module
}

private fun Symbol.containerName(): String? = when (this) {
    is Symbol.Method -> owner?.nameWithoutPackage ?: owner?.name
    is Symbol.Field -> owner?.nameWithoutPackage ?: owner?.name
    is Symbol.Property -> owner?.nameWithoutPackage ?: owner?.name
    is Symbol.Class -> packageName
    is Symbol.Import -> packageName
    is Symbol.Variable -> null
}

private fun Symbol.detail(): String? = when (this) {
    is Symbol.Method -> signature
    is Symbol.Field -> type?.nameWithoutPackage
    is Symbol.Property -> type?.nameWithoutPackage
    is Symbol.Class -> fullyQualifiedName
    is Symbol.Variable -> type?.nameWithoutPackage
    is Symbol.Import -> importedName
}

private fun Symbol.toLspRange(): Range? {
    val range = node.safeRange().getOrNull()
    if (range != null) {
        return range.toLspRange()
    }

    val start = position.getOrNull()?.let { Position(it.line.value, it.column.value) } ?: return null
    return Range(start, start)
}
