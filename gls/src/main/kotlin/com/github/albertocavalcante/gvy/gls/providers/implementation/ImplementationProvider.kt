// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.implementation

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import com.github.albertocavalcante.gvy.semantics.db.ImplementationDocument
import com.github.albertocavalcante.gvy.semantics.db.ImplementationQuery
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/** Implementation navigation consumes detached semantic facts, never per-file AST caches. */
class ImplementationProvider(private val compilationService: GroovyCompilationService) {
    fun provideImplementations(uri: String, position: Position): Flow<Location> {
        if (position.line < 0 || position.character < 0) return emptyFlow()
        val origin = try {
            URI.create(uri)
        } catch (_: IllegalArgumentException) {
            return emptyFlow()
        }
        val snapshot = compilationService.getWorkspaceSymbolIndex()?.implementationSnapshot.orEmpty()
        return provideImplementations(origin, position, snapshot)
    }

    internal fun provideImplementations(
        origin: URI,
        position: Position,
        snapshot: Map<URI, ImplementationDocument>,
    ): Flow<Location> {
        val document = snapshot[origin] ?: return emptyFlow()
        val indices = mutableMapOf<URI, TextIndex>()
        val occurrence = indexFor(origin, snapshot, indices)?.let { targetAt(document, position, it) }
        val locations = occurrence?.let { ImplementationQuery.find(snapshot, origin, it) }.orEmpty()
        return locations.mapNotNull { location ->
            val target = indexFor(location.uri, snapshot, indices) ?: return@mapNotNull null
            val range = target.utf16Range(location.range.toCodePointRange()) ?: return@mapNotNull null
            Location(location.uri.toString(), range)
        }.asFlow()
    }

    private fun indexFor(
        uri: URI,
        snapshot: Map<URI, ImplementationDocument>,
        indices: MutableMap<URI, TextIndex>,
    ): TextIndex? = indices[uri] ?: snapshot[uri]?.sourceText?.let { text ->
        TextIndex(text).also { indices[uri] = it }
    }

    private fun targetAt(document: ImplementationDocument, position: Position, index: TextIndex): SymbolOccurrence? {
        val line = index.lineRange(position.line) ?: return null
        val protocol = Position(position.line, minOf(position.character, line.end.character))
        val groovy = index.codePointPosition(protocol) ?: return null
        return document.document.occurrences.filter {
            it.role != OccurrenceRole.IMPORT && it.range.contains(groovy.line, groovy.character)
        }
            .minWithOrNull(
                compareBy({ it.range.endLine - it.range.startLine }, {
                    it.range.endColumn - it.range.startColumn
                }),
            )
            ?.takeIf {
                when (it.role) {
                    OccurrenceRole.DEFINITION -> declarationContains(document, it, protocol, index)
                    OccurrenceRole.TYPE_REF -> importReferenceContains(document, it, protocol, index)
                    else -> true
                }
            }
    }

    // Definitions cover whole AST nodes; a cursor in a body/comment must not select its enclosing declaration.
    private fun declarationContains(
        document: ImplementationDocument,
        occurrence: SymbolOccurrence,
        position: Position,
        index: TextIndex,
    ): Boolean {
        val symbol = document.document.symbols.firstOrNull {
            it.symbol == occurrence.symbol && it.range == occurrence.range
        } ?: return false
        val range = index.utf16Range(occurrence.range.toCodePointRange()) ?: return false
        val name = firstNameOccurrence(index, range, symbol.name.substringAfterLast('$'))
        return name != null && name.contains(position)
    }

    private fun importReferenceContains(
        document: ImplementationDocument,
        occurrence: SymbolOccurrence,
        position: Position,
        index: TextIndex,
    ): Boolean {
        val imported =
            document.document.symbols.firstOrNull { it.kind == SymbolKind.IMPORT && it.range == occurrence.range }
                ?: return true
        val range = index.utf16Range(occurrence.range.toCodePointRange()) ?: return false
        val name = occurrence.symbol.substringAfterLast('/').removeSuffix("#").substringAfterLast('$')
        // A normal import aliases its type; a static import aliases a member, which must not select the type.
        val classImportSymbol = "import#${occurrence.symbol.removeSuffix("#")}"
        val identifiers = if (imported.symbol == classImportSymbol) listOf(name, imported.name) else listOf(name)
        return identifiers.any { identifier ->
            firstNameOccurrence(index, range, identifier)?.contains(position) == true
        }
    }

    private fun Range.contains(position: Position): Boolean =
        compareValuesBy(position, start, { it.line }, { it.character }) >= 0 &&
            compareValuesBy(position, end, { it.line }, { it.character }) < 0

    private fun com.github.albertocavalcante.gvy.semantics.db.Range.toCodePointRange(): Range =
        Range(Position(startLine, startColumn), Position(endLine, endColumn))
}
