package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/**
 * Comprehensive tests for GroovySemanticDB following TDD approach.
 * Tests data structures, database operations, and indexing.
 */
class GroovySemanticDBTest {

    private lateinit var db: GroovySemanticDB
    private val testUri1 = URI.create("file:///Test1.groovy")
    private val testUri2 = URI.create("file:///Test2.groovy")

    @BeforeEach
    fun setup() {
        db = GroovySemanticDB()
    }

    @Nested
    @DisplayName("Range Tests")
    inner class RangeTests {

        @Test
        fun `Range creation with valid values`() {
            val range = Range(0, 0, 0, 10)
            assertEquals(0, range.startLine)
            assertEquals(0, range.startColumn)
            assertEquals(0, range.endLine)
            assertEquals(10, range.endColumn)
        }

        @Test
        fun `Range creation with multi-line span`() {
            val range = Range(0, 5, 5, 10)
            assertEquals(0, range.startLine)
            assertEquals(5, range.startColumn)
            assertEquals(5, range.endLine)
            assertEquals(10, range.endColumn)
        }

        @Test
        fun `Range rejects negative startLine`() {
            assertThrows<IllegalArgumentException> {
                Range(-1, 0, 0, 10)
            }
        }

        @Test
        fun `Range rejects negative startColumn`() {
            assertThrows<IllegalArgumentException> {
                Range(0, -1, 0, 10)
            }
        }

        @Test
        fun `Range rejects startLine greater than endLine`() {
            assertThrows<IllegalArgumentException> {
                Range(5, 0, 3, 10)
            }
        }

        @Test
        fun `Range rejects startColumn greater than or equal to endColumn on same line`() {
            assertThrows<IllegalArgumentException> {
                Range(0, 10, 0, 10)
            }
            assertThrows<IllegalArgumentException> {
                Range(0, 15, 0, 10)
            }
        }

        @Test
        fun `Range contains returns true for position inside range`() {
            val range = Range(5, 10, 5, 20)
            assertTrue(range.contains(5, 10))
            assertTrue(range.contains(5, 15))
            assertTrue(range.contains(5, 19))
        }

        @Test
        fun `Range contains returns false for position outside range`() {
            val range = Range(5, 10, 5, 20)
            assertTrue(!range.contains(4, 15)) // Line before
            assertTrue(!range.contains(6, 15)) // Line after
            assertTrue(!range.contains(5, 5)) // Before start column
            assertTrue(!range.contains(5, 20)) // At end column (exclusive)
            assertTrue(!range.contains(5, 25)) // After end column
        }

        @Test
        fun `Range contains works for multi-line range`() {
            val range = Range(5, 10, 10, 15)
            assertTrue(range.contains(5, 10)) // Start
            assertTrue(range.contains(7, 5)) // Middle
            assertTrue(range.contains(10, 10)) // Near end
            assertTrue(!range.contains(10, 15)) // At end (exclusive)
            assertTrue(!range.contains(4, 10)) // Before
            assertTrue(!range.contains(11, 5)) // After
        }

        @Test
        fun `Range overlaps returns true for overlapping ranges`() {
            val range1 = Range(5, 10, 5, 20)
            val range2 = Range(5, 15, 5, 25)
            assertTrue(range1.overlaps(range2))
            assertTrue(range2.overlaps(range1))
        }

        @Test
        fun `Range overlaps returns false for non-overlapping ranges`() {
            val range1 = Range(5, 10, 5, 20)
            val range2 = Range(5, 20, 5, 30)
            assertTrue(!range1.overlaps(range2))
            assertTrue(!range2.overlaps(range1))
        }

        @Test
        fun `Range overlaps works for multi-line ranges`() {
            val range1 = Range(5, 10, 10, 20)
            val range2 = Range(8, 5, 12, 15)
            assertTrue(range1.overlaps(range2))
            assertTrue(range2.overlaps(range1))
        }
    }

    @Nested
    @DisplayName("SemanticDocument Tests")
    inner class SemanticDocumentTests {

        @Test
        fun `Create empty SemanticDocument`() {
            val doc = SemanticDocument(testUri1, emptyList(), emptyList())
            assertEquals(testUri1, doc.uri)
            assertEquals(0, doc.symbols.size)
            assertEquals(0, doc.occurrences.size)
        }

        @Test
        fun `SemanticDocument with symbols`() {
            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(symbol), emptyList())

            assertEquals(1, doc.symbols.size)
            assertEquals("MyClass", doc.symbols[0].name)
        }

        @Test
        fun `findSymbol returns correct symbol`() {
            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(symbol), emptyList())

            val found = doc.findSymbol("com/example/MyClass#")
            assertNotNull(found)
            assertEquals("MyClass", found?.name)
        }

        @Test
        fun `findSymbol returns null for non-existent symbol`() {
            val doc = SemanticDocument(testUri1, emptyList(), emptyList())
            val found = doc.findSymbol("non/existent#")
            assertNull(found)
        }

        @Test
        fun `findOccurrences returns matching occurrences`() {
            val occurrence1 = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )
            val occurrence2 = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(10, 5, 10, 15),
                role = OccurrenceRole.CALL,
            )
            val occurrence3 = SymbolOccurrence(
                symbol = "com/example/Other#",
                range = Range(15, 0, 15, 5),
                role = OccurrenceRole.REFERENCE,
            )

            val doc = SemanticDocument(testUri1, emptyList(), listOf(occurrence1, occurrence2, occurrence3))

            val found = doc.findOccurrences("com/example/MyClass#myMethod().")
            assertEquals(2, found.size)
            assertTrue(found.all { it.symbol == "com/example/MyClass#myMethod()." })
        }

        @Test
        fun `findSymbolsByKind returns symbols of specified kind`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(2, 4, 2, 20),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val fieldSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myField.",
                kind = SymbolKind.FIELD,
                range = Range(1, 4, 1, 15),
                name = "myField",
                owner = "com/example/MyClass#",
            )

            val doc = SemanticDocument(testUri1, listOf(classSymbol, methodSymbol, fieldSymbol), emptyList())

            val classes = doc.findSymbolsByKind(SymbolKind.CLASS)
            assertEquals(1, classes.size)
            assertEquals("MyClass", classes[0].name)

            val methods = doc.findSymbolsByKind(SymbolKind.METHOD)
            assertEquals(1, methods.size)
            assertEquals("myMethod", methods[0].name)

            val fields = doc.findSymbolsByKind(SymbolKind.FIELD)
            assertEquals(1, fields.size)
            assertEquals("myField", fields[0].name)
        }

        @Test
        fun `findOccurrencesByRole returns occurrences of specified role`() {
            val defOccurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myField.",
                range = Range(1, 4, 1, 15),
                role = OccurrenceRole.DEFINITION,
            )
            val refOccurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myField.",
                range = Range(5, 10, 5, 17),
                role = OccurrenceRole.REFERENCE,
            )
            val callOccurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(8, 0, 8, 10),
                role = OccurrenceRole.CALL,
            )

            val doc = SemanticDocument(testUri1, emptyList(), listOf(defOccurrence, refOccurrence, callOccurrence))

            val definitions = doc.findOccurrencesByRole(OccurrenceRole.DEFINITION)
            assertEquals(1, definitions.size)

            val references = doc.findOccurrencesByRole(OccurrenceRole.REFERENCE)
            assertEquals(1, references.size)

            val calls = doc.findOccurrencesByRole(OccurrenceRole.CALL)
            assertEquals(1, calls.size)
        }
    }

    @Nested
    @DisplayName("GroovySemanticDB Basic Operations")
    inner class BasicOperations {

        @Test
        fun `getDocument returns null for non-existent URI`() {
            val doc = db.getDocument(testUri1)
            assertNull(doc)
        }

        @Test
        fun `updateDocument stores and retrieves document`() {
            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(symbol), emptyList())

            db.updateDocument(testUri1, doc)

            val retrieved = db.getDocument(testUri1)
            assertNotNull(retrieved)
            assertEquals(testUri1, retrieved?.uri)
            assertEquals(1, retrieved?.symbols?.size)
            assertEquals("MyClass", retrieved?.symbols?.get(0)?.name)
        }

        @Test
        fun `updateDocument replaces existing document`() {
            val doc1 = SemanticDocument(testUri1, emptyList(), emptyList())
            db.updateDocument(testUri1, doc1)

            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val doc2 = SemanticDocument(testUri1, listOf(symbol), emptyList())
            db.updateDocument(testUri1, doc2)

            val retrieved = db.getDocument(testUri1)
            assertNotNull(retrieved)
            assertEquals(1, retrieved?.symbols?.size)
        }

        @Test
        fun `removeDocument removes document from database`() {
            val doc = SemanticDocument(testUri1, emptyList(), emptyList())
            db.updateDocument(testUri1, doc)

            db.removeDocument(testUri1)

            val retrieved = db.getDocument(testUri1)
            assertNull(retrieved)
        }

        @Test
        fun `getAllDocuments returns all stored documents`() {
            val doc1 = SemanticDocument(testUri1, emptyList(), emptyList())
            val doc2 = SemanticDocument(testUri2, emptyList(), emptyList())

            db.updateDocument(testUri1, doc1)
            db.updateDocument(testUri2, doc2)

            val allDocs = db.getAllDocuments()
            assertEquals(2, allDocs.size)
            assertTrue(allDocs.containsKey(testUri1))
            assertTrue(allDocs.containsKey(testUri2))
        }

        @Test
        fun `clear removes all documents`() {
            val doc1 = SemanticDocument(testUri1, emptyList(), emptyList())
            val doc2 = SemanticDocument(testUri2, emptyList(), emptyList())

            db.updateDocument(testUri1, doc1)
            db.updateDocument(testUri2, doc2)

            db.clear()

            val allDocs = db.getAllDocuments()
            assertEquals(0, allDocs.size)
        }
    }

    @Nested
    @DisplayName("Symbol Indexing and Lookup")
    inner class SymbolIndexing {

        @Test
        fun `findSymbolDefinition returns symbol from index`() {
            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(symbol), emptyList())
            db.updateDocument(testUri1, doc)

            val found = db.findSymbolDefinition("com/example/MyClass#")
            assertNotNull(found)
            assertEquals(testUri1, found?.first)
            assertEquals("MyClass", found?.second?.name)
        }

        @Test
        fun `findSymbolDefinition returns null for non-existent symbol`() {
            val doc = SemanticDocument(testUri1, emptyList(), emptyList())
            db.updateDocument(testUri1, doc)

            val found = db.findSymbolDefinition("non/existent#")
            assertNull(found)
        }

        @Test
        fun `findAllSymbolDefinitions returns all definitions across files`() {
            // Same symbol defined in two files (edge case)
            val symbol1 = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val symbol2 = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )

            val doc1 = SemanticDocument(testUri1, listOf(symbol1), emptyList())
            val doc2 = SemanticDocument(testUri2, listOf(symbol2), emptyList())

            db.updateDocument(testUri1, doc1)
            db.updateDocument(testUri2, doc2)

            val found = db.findAllSymbolDefinitions("com/example/MyClass#")
            assertEquals(2, found.size)
        }

        @Test
        fun `symbol index updated when document is replaced`() {
            val symbol1 = SymbolInfo(
                symbol = "com/example/OldClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "OldClass",
                owner = null,
            )
            val doc1 = SemanticDocument(testUri1, listOf(symbol1), emptyList())
            db.updateDocument(testUri1, doc1)

            // Replace with new document with different symbol
            val symbol2 = SymbolInfo(
                symbol = "com/example/NewClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "NewClass",
                owner = null,
            )
            val doc2 = SemanticDocument(testUri1, listOf(symbol2), emptyList())
            db.updateDocument(testUri1, doc2)

            // Old symbol should not be found
            val oldFound = db.findSymbolDefinition("com/example/OldClass#")
            assertNull(oldFound)

            // New symbol should be found
            val newFound = db.findSymbolDefinition("com/example/NewClass#")
            assertNotNull(newFound)
            assertEquals("NewClass", newFound?.second?.name)
        }

        @Test
        fun `symbol index cleaned when document is removed`() {
            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(symbol), emptyList())
            db.updateDocument(testUri1, doc)

            db.removeDocument(testUri1)

            val found = db.findSymbolDefinition("com/example/MyClass#")
            assertNull(found)
        }
    }

    @Nested
    @DisplayName("Occurrence Indexing and Lookup")
    inner class OccurrenceIndexing {

        @Test
        fun `findAllOccurrences returns occurrences from index`() {
            val occurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )
            val doc = SemanticDocument(testUri1, emptyList(), listOf(occurrence))
            db.updateDocument(testUri1, doc)

            val found = db.findAllOccurrences("com/example/MyClass#myMethod().")
            assertEquals(1, found.size)
            assertEquals(testUri1, found[0].first)
            assertEquals(OccurrenceRole.CALL, found[0].second.role)
        }

        @Test
        fun `findAllOccurrences returns occurrences across multiple files`() {
            val occurrence1 = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )
            val occurrence2 = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(10, 5, 10, 15),
                role = OccurrenceRole.CALL,
            )

            val doc1 = SemanticDocument(testUri1, emptyList(), listOf(occurrence1))
            val doc2 = SemanticDocument(testUri2, emptyList(), listOf(occurrence2))

            db.updateDocument(testUri1, doc1)
            db.updateDocument(testUri2, doc2)

            val found = db.findAllOccurrences("com/example/MyClass#myMethod().")
            assertEquals(2, found.size)
        }

        @Test
        fun `occurrence index updated when document is replaced`() {
            val occurrence1 = SymbolOccurrence(
                symbol = "com/example/OldMethod#",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )
            val doc1 = SemanticDocument(testUri1, emptyList(), listOf(occurrence1))
            db.updateDocument(testUri1, doc1)

            val occurrence2 = SymbolOccurrence(
                symbol = "com/example/NewMethod#",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )
            val doc2 = SemanticDocument(testUri1, emptyList(), listOf(occurrence2))
            db.updateDocument(testUri1, doc2)

            val oldFound = db.findAllOccurrences("com/example/OldMethod#")
            assertEquals(0, oldFound.size)

            val newFound = db.findAllOccurrences("com/example/NewMethod#")
            assertEquals(1, newFound.size)
        }
    }

    @Nested
    @DisplayName("Position-based Queries")
    inner class PositionQueries {

        @Test
        fun `findSymbolAtPosition returns symbol at position`() {
            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(5, 10, 5, 20),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(symbol), emptyList())
            db.updateDocument(testUri1, doc)

            val found = db.findSymbolAtPosition(testUri1, 5, 15)
            assertNotNull(found)
            assertEquals("MyClass", found?.name)
        }

        @Test
        fun `findSymbolAtPosition returns null for position outside symbols`() {
            val symbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(5, 10, 5, 20),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(symbol), emptyList())
            db.updateDocument(testUri1, doc)

            val found = db.findSymbolAtPosition(testUri1, 10, 15)
            assertNull(found)
        }

        @Test
        fun `findOccurrenceAtPosition returns occurrence at position`() {
            val occurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(8, 5, 8, 15),
                role = OccurrenceRole.CALL,
            )
            val doc = SemanticDocument(testUri1, emptyList(), listOf(occurrence))
            db.updateDocument(testUri1, doc)

            val found = db.findOccurrenceAtPosition(testUri1, 8, 10)
            assertNotNull(found)
            assertEquals(OccurrenceRole.CALL, found?.role)
        }
    }

    @Nested
    @DisplayName("Statistics and Queries")
    inner class StatisticsQueries {

        @Test
        fun `getStatistics returns correct counts for empty database`() {
            val stats = db.getStatistics()
            assertEquals(0, stats.documentCount)
            assertEquals(0, stats.totalSymbols)
            assertEquals(0, stats.totalOccurrences)
        }

        @Test
        fun `getStatistics returns correct counts for populated database`() {
            val symbol1 = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val symbol2 = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(2, 4, 2, 20),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val occurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )

            val doc1 = SemanticDocument(testUri1, listOf(symbol1, symbol2), listOf(occurrence))
            db.updateDocument(testUri1, doc1)

            val stats = db.getStatistics()
            assertEquals(1, stats.documentCount)
            assertEquals(2, stats.totalSymbols)
            assertEquals(1, stats.totalOccurrences)
            assertEquals(1, stats.symbolsByKind[SymbolKind.CLASS])
            assertEquals(1, stats.symbolsByKind[SymbolKind.METHOD])
            assertEquals(1, stats.occurrencesByRole[OccurrenceRole.CALL])
        }

        @Test
        fun `findSymbolsByKind returns symbols of specified kind`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 0, 10),
                name = "MyClass",
                owner = null,
            )
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(2, 4, 2, 20),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )

            val doc = SemanticDocument(testUri1, listOf(classSymbol, methodSymbol), emptyList())
            db.updateDocument(testUri1, doc)

            val classes = db.findSymbolsByKind(testUri1, SymbolKind.CLASS)
            assertEquals(1, classes.size)
            assertEquals("MyClass", classes[0].name)

            val methods = db.findSymbolsByKind(testUri1, SymbolKind.METHOD)
            assertEquals(1, methods.size)
            assertEquals("myMethod", methods[0].name)
        }

        @Test
        fun `findOccurrencesByRole returns occurrences of specified role`() {
            val callOccurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )
            val refOccurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myField.",
                range = Range(8, 5, 8, 12),
                role = OccurrenceRole.REFERENCE,
            )

            val doc = SemanticDocument(testUri1, emptyList(), listOf(callOccurrence, refOccurrence))
            db.updateDocument(testUri1, doc)

            val calls = db.findOccurrencesByRole(testUri1, OccurrenceRole.CALL)
            assertEquals(1, calls.size)

            val refs = db.findOccurrencesByRole(testUri1, OccurrenceRole.REFERENCE)
            assertEquals(1, refs.size)
        }
    }
}
