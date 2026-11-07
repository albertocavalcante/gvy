package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImplementationSnapshotTest {
    private val uri = URI.create("file:///Contract.groovy")
    private val range = Range(0, 0, 2, 1)

    @Test
    fun `snapshots retain their source and hierarchy after replacement and removal`() {
        val db = GroovySemanticDB()
        val original = facts("first/Contract#")
        db.updateDocument(uri, document(), "first source", original)
        val captured = db.implementationSnapshot

        db.updateDocument(uri, document(), "second source", facts("second/Contract#"))
        assertEquals("second/Contract#", db.implementationSnapshot.getValue(uri).classes.single().symbol)
        db.removeDocument(uri)
        assertTrue(db.implementationSnapshot.isEmpty())
        assertEquals("first source", captured.getValue(uri).sourceText)
        assertEquals(original.classes, captured.getValue(uri).classes)
    }

    @Test
    fun `metadata updates never retain hierarchy facts from an earlier compilation`() {
        val db = GroovySemanticDB()
        db.updateDocument(uri, document(), "source", facts("Contract#"))
        db.updateDocument(uri, document(), "replacement")
        assertTrue(db.implementationSnapshot.getValue(uri).classes.isEmpty())
        assertTrue(db.implementationSnapshot.getValue(uri).hierarchy.isEmpty())
        db.updateDocument(uri, document())
        assertEquals(null, db.implementationSnapshot.getValue(uri).sourceText)
    }

    @Test
    fun `writer and reader collection mutations cannot change published hierarchy facts`() {
        val db = GroovySemanticDB()
        val parameters = mutableListOf("first.Value")
        val references = mutableListOf(ImplementationTypeReference("T", isVariable = true))
        val defaults = mutableMapOf("T" to "first.Value")
        val arguments = mutableListOf(ImplementationTypeReference("T", isVariable = true))
        val parentArguments = mutableMapOf("Base#" to arguments)
        val methods = mutableListOf(method(parameters).copy(parameterReferences = references))
        val parents = mutableListOf("Base#")
        val types = mutableListOf(ImplementationClass("Contract#", range))
        val graph = mutableMapOf(
            "Contract#" to ImplementationType(true, true, null, parents, methods, defaults, parentArguments),
        )
        db.updateDocument(uri, document(), "source", ImplementationFacts.Snapshot(types, graph))
        parameters[0] = "other.Value"
        references.clear()
        defaults.clear()
        arguments.clear()
        parentArguments.clear()
        parents.clear()
        methods.clear()
        types.clear()
        graph.clear()

        val snapshot = db.implementationSnapshot
        val stored = snapshot.getValue(uri).hierarchy.getValue("Contract#")
        assertEquals(listOf("first.Value"), stored.methods.single().parameterTypes)
        assertEquals(listOf("Base#"), stored.interfaces)
        assertEquals(mapOf("T" to "first.Value"), stored.parameterDefaults)
        assertEquals(
            listOf(ImplementationTypeReference("T", isVariable = true)),
            stored.methods.single().parameterReferences,
        )
        assertEquals(stored.methods.single().parameterReferences, stored.parentArguments.getValue("Base#"))
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.getValue(uri).classes as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.getValue(uri).hierarchy as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (stored.methods as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (stored.interfaces as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (stored.methods.single().parameterTypes as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> { (stored.parameterDefaults as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (stored.parentArguments as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (stored.parentArguments.getValue("Base#") as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (stored.methods.single().parameterReferences as MutableList).clear()
        }
    }

    private fun document(): SemanticDocument = SemanticDocument(uri, emptyList(), emptyList())

    private fun facts(symbol: String): ImplementationFacts.Snapshot = ImplementationFacts.Snapshot(
        listOf(ImplementationClass(symbol, range)),
        mapOf(symbol to ImplementationType(true, true, null, emptyList(), emptyList())),
    )

    private fun method(parameters: List<String>): ImplementationMethod =
        ImplementationMethod("Contract#greet(Value).", "greet", parameters, range, true, false, false)
}
