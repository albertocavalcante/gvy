package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImplementationMethodSignaturesTest {
    private val uri = URI.create("file:///Parent.groovy")
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

    private fun method(parameter: String): ImplementationMethod =
        ImplementationMethod(symbol, "choose", listOf(parameter), range, false, false, false)
}
