package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImplementationMethodSignaturesTest {
    private val uri = URI.create("file:///Parent.groovy")
    private val other = URI.create("file:///Other.groovy")
    private val range = Range(0, 0, 2, 1)
    private val owner = "Parent#"
    private val symbol = "Parent#choose(Date)."

    @Test
    fun `erased symbol collisions retain both qualified signatures after source removal`() {
        val db = GroovySemanticDB()
        val facts = ImplementationFacts.Snapshot(
            listOf(ImplementationClass(owner, range)),
            mapOf(
                owner to ImplementationType(
                    false,
                    false,
                    null,
                    emptyList(),
                    listOf(method("java.util.Date"), method("java.sql.Date")),
                ),
            ),
        )
        db.updateDocument(uri, SemanticDocument(uri, emptyList(), emptyList()), "source", facts)
        val captured = db.implementationSnapshot.getValue(uri)
        db.removeDocument(uri)

        val signatures = ImplementationMethodSignatures.parameterTypesBySymbol(captured, owner)

        assertEquals(mapOf(symbol to listOf(listOf("java.util.Date"), listOf("java.sql.Date"))), signatures)
        assertTrue(db.implementationSnapshot.isEmpty())
        assertFailsWith<UnsupportedOperationException> { (signatures as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (signatures.getValue(symbol) as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (signatures.getValue(symbol).first() as MutableList).clear()
        }
    }

    @Test
    fun `classpath ancestry facts cannot masquerade as a source owned class`() {
        val document = ImplementationDocument(
            SemanticDocument(uri, emptyList(), emptyList()),
            "source",
            emptyList(),
            mapOf(owner to ImplementationType(false, false, null, emptyList(), listOf(method("java.util.Date")))),
        )

        assertTrue(ImplementationMethodSignatures.parameterTypesBySymbol(document, owner).isEmpty())
    }

    @Test
    fun `single document lookup returns only the unambiguous defining document`() {
        val db = GroovySemanticDB()
        val parent = SymbolInfo(owner, SymbolKind.CLASS, range, "Parent", null)
        val facts = ImplementationFacts.Snapshot(
            listOf(ImplementationClass(owner, range)),
            mapOf(owner to ImplementationType(false, false, null, emptyList(), listOf(method("java.util.Date")))),
        )
        db.updateDocument(uri, SemanticDocument(uri, listOf(parent), emptyList()), "source", facts)
        db.updateDocument(other, SemanticDocument(other, emptyList(), emptyList()), "other", facts)

        val found = assertNotNull(db.findImplementationDocument(owner))

        assertEquals(uri, found.document.uri)
        assertEquals(mapOf(symbol to listOf(listOf("java.util.Date"))), parameterTypes(found))
        assertNull(db.findImplementationDocument("Missing#"))

        db.updateDocument(other, SemanticDocument(other, listOf(parent), emptyList()), "other", facts)
        assertNull(db.findImplementationDocument(owner), "A class defined in two files is ambiguous")
    }

    private fun parameterTypes(document: ImplementationDocument) =
        ImplementationMethodSignatures.parameterTypesBySymbol(document, owner)

    private fun method(parameter: String): ImplementationMethod =
        ImplementationMethod(symbol, "choose", listOf(parameter), range, false, false, false)
}
