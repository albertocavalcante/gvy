// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence

/** Ordinary type imports only: static member imports and star imports cannot supply type aliases. */
internal fun SemanticDocument.importedTypeNames(classSymbol: String): Set<String> {
    if (!classSymbol.endsWith('#')) return emptySet()
    val importSymbol = "import#${classSymbol.removeSuffix("#")}" // Preserve binary nested names, including '$'.
    return symbols.filter { binding ->
        binding.kind == SymbolKind.IMPORT && binding.symbol == importSymbol &&
            occurrences.any { occurrence ->
                occurrence.symbol == classSymbol && occurrence.role == OccurrenceRole.TYPE_REF &&
                    binding.range.encloses(occurrence.range)
            }
    }.mapTo(mutableSetOf()) { it.name }
}

/** The alias declaration in an import is a binding; the imported type itself keeps its canonical spelling. */
internal fun SemanticDocument.isImportTypeReference(occurrence: SymbolOccurrence): Boolean =
    occurrence.role == OccurrenceRole.TYPE_REF && symbols.any {
        it.kind == SymbolKind.IMPORT && it.range.encloses(occurrence.range)
    }

private fun Range.encloses(other: Range): Boolean = contains(other.startLine, other.startColumn) &&
    (contains(other.endLine, other.endColumn) || endLine == other.endLine && endColumn == other.endColumn)
