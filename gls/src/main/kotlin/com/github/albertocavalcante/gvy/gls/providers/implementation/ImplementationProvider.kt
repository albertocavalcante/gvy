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
        if (position.line < 0 || position.character < 0) return emptyFlow()
        val occurrence = snapshot[origin]?.let { targetAt(it, position) }
        val locations = occurrence?.let { ImplementationQuery.find(snapshot, origin, it) }.orEmpty()
        return locations.map { Location(it.uri.toString(), it.range.toLsp()) }.asFlow()
    }

    private fun targetAt(document: ImplementationDocument, position: Position): SymbolOccurrence? =
        document.document.occurrences.filter {
            it.role != OccurrenceRole.IMPORT && it.range.contains(position.line, position.character)
        }
            .minWithOrNull(
                compareBy({ it.range.endLine - it.range.startLine }, {
                    it.range.endColumn -
                        it.range.startColumn
                }),
            )
            ?.takeIf {
                when (it.role) {
                    OccurrenceRole.DEFINITION -> declarationContains(document, it, position)
                    OccurrenceRole.TYPE_REF -> importReferenceContains(document, it, position)
                    else -> true
                }
            }

    // Definitions cover whole AST nodes; a cursor in a body/comment must not select its enclosing declaration.
    private fun declarationContains(
        document: ImplementationDocument,
        occurrence: SymbolOccurrence,
        position: Position,
    ): Boolean {
        val symbol = document.document.symbols.firstOrNull {
            it.symbol == occurrence.symbol && it.range == occurrence.range
        } ?: return false
        val text = document.sourceText ?: return false
        val name = firstNameOccurrence(TextIndex(text), occurrence.range.toLsp(), symbol.name.substringAfterLast('$'))
        return name != null && name.contains(position)
    }

    private fun importReferenceContains(
        document: ImplementationDocument,
        occurrence: SymbolOccurrence,
        position: Position,
    ): Boolean {
        val imported =
            document.document.symbols.firstOrNull { it.kind == SymbolKind.IMPORT && it.range == occurrence.range }
                ?: return true
        val text = document.sourceText ?: return false
        val name = occurrence.symbol.substringAfterLast('/').removeSuffix("#").substringAfterLast('$')
        // A normal import aliases its type; a static import aliases a member, which must not select the type.
        val classImportSymbol = "import#${occurrence.symbol.removeSuffix("#")}"
        val identifiers = if (imported.symbol == classImportSymbol) listOf(name, imported.name) else listOf(name)
        return identifiers.any { identifier ->
            firstNameOccurrence(TextIndex(text), occurrence.range.toLsp(), identifier)?.contains(position) == true
        }
    }

    private fun Range.contains(position: Position): Boolean =
        compareValuesBy(position, start, { it.line }, { it.character }) >= 0 &&
            compareValuesBy(position, end, { it.line }, { it.character }) < 0

    private fun com.github.albertocavalcante.gvy.semantics.db.Range.toLsp(): Range =
        Range(Position(startLine, startColumn), Position(endLine, endColumn))
}
