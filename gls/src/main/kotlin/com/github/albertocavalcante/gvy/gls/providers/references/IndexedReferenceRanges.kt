// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import com.github.albertocavalcante.gvy.gls.utils.firstNameOccurrence
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/** Interpret raw semantic ranges only against their captured import bindings and source text. */
internal fun SemanticDocumentSnapshot.referenceLocations(
    uri: URI,
    symbol: String,
    name: String,
    includeDeclaration: Boolean,
): List<Location> {
    val text = sourceText?.let(::TextIndex) ?: return emptyList()
    val aliases = document.importedTypeNames(symbol)
    return document.findOccurrences(symbol).mapNotNull { reference ->
        if (!includeDeclaration && reference.role == OccurrenceRole.DEFINITION) return@mapNotNull null
        val raw = reference.range
        val range = text.utf16Range(
            Range(Position(raw.startLine, raw.startColumn), Position(raw.endLine, raw.endColumn)),
        ) ?: return@mapNotNull null
        val typeUse = symbol.endsWith('#') && reference.role != OccurrenceRole.DEFINITION &&
            !document.isImportTypeReference(reference)
        val identifier = if (typeUse) {
            typeReferenceRange(text, range, aliases + name)
        } else {
            firstNameOccurrence(text, range, name)
        }
        identifier?.let { Location(uri.toString(), it) }
    }
}
