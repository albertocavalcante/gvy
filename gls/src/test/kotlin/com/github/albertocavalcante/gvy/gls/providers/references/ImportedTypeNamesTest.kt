// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportedTypeNamesTest {
    private val span = Range(0, 0, 0, 30)
    private val symbol = "p/Outer\$Inner#"
    private val binding = SymbolInfo("import#p/Outer\$Inner", SymbolKind.IMPORT, span, "Nested", null)
    private val occurrence = SymbolOccurrence(symbol, span, OccurrenceRole.TYPE_REF)

    private fun document(binding: SymbolInfo = this.binding, occurrence: SymbolOccurrence = this.occurrence) =
        SemanticDocument(URI.create("file:///Caller.groovy"), listOf(binding), listOf(occurrence))

    @Test
    fun `binary nested type identities cannot collapse into package names`() {
        assertEquals(setOf("Nested"), document().importedTypeNames(symbol))
        assertTrue(document().importedTypeNames("p/Outer/Inner#").isEmpty())
    }

    @Test
    fun `static member imports and unproved type imports cannot supply aliases`() {
        assertTrue(document(binding.copy(symbol = "import#p/Outer\$Inner.member")).importedTypeNames(symbol).isEmpty())
        assertTrue(
            document(occurrence = occurrence.copy(symbol = "other/Outer\$Inner#"))
                .importedTypeNames(symbol).isEmpty(),
        )
        assertTrue(
            document(occurrence = occurrence.copy(role = OccurrenceRole.IMPORT))
                .importedTypeNames(symbol).isEmpty(),
        )
    }

    @Test
    fun `import bindings are distinguished from alias uses in the same document`() {
        val document = document()
        assertTrue(document.isImportTypeReference(occurrence))
        assertFalse(document.isImportTypeReference(occurrence.copy(range = Range(1, 0, 1, 6))))
        assertFalse(document.isImportTypeReference(occurrence.copy(role = OccurrenceRole.CALL)))
    }
}
