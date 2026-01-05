package com.github.albertocavalcante.groovylsp.indexing

import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Comprehensive tests for WorkspaceSymbolIndex following TDD approach.
 * Tests symbol lookup, reference finding, class hierarchy, and member lookup.
 */
class WorkspaceSymbolIndexTest {

    private lateinit var semanticDb: GroovySemanticDB
    private lateinit var index: WorkspaceSymbolIndex

    private val testUri1 = URI.create("file:///workspace/MyClass.groovy")
    private val testUri2 = URI.create("file:///workspace/SubClass.groovy")
    private val testUri3 = URI.create("file:///workspace/Client.groovy")

    @BeforeEach
    fun setup() {
        semanticDb = GroovySemanticDB()
        index = WorkspaceSymbolIndex(semanticDb)
    }

    @Nested
    @DisplayName("Symbol Lookup")
    inner class SymbolLookupTests {

        @Test
        fun `findSymbol returns null for non-existent symbol`() {
            val found = index.findSymbol("non/existent#")
            assertNull(found)
        }

        @Test
        fun `findSymbol returns symbol from workspace`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val found = index.findSymbol("com/example/MyClass#")
            assertNotNull(found)
            assertEquals("MyClass", found?.name)
            assertEquals(SymbolKind.CLASS, found?.kind)
        }

        @Test
        fun `findSymbol returns method symbol`() {
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod(String).",
                kind = SymbolKind.METHOD,
                range = Range(5, 4, 7, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val doc = SemanticDocument(testUri1, listOf(methodSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val found = index.findSymbol("com/example/MyClass#myMethod(String).")
            assertNotNull(found)
            assertEquals("myMethod", found?.name)
            assertEquals(SymbolKind.METHOD, found?.kind)
        }

        @Test
        fun `findSymbol returns field symbol`() {
            val fieldSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myField.",
                kind = SymbolKind.FIELD,
                range = Range(2, 4, 2, 15),
                name = "myField",
                owner = "com/example/MyClass#",
            )
            val doc = SemanticDocument(testUri1, listOf(fieldSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val found = index.findSymbol("com/example/MyClass#myField.")
            assertNotNull(found)
            assertEquals("myField", found?.name)
            assertEquals(SymbolKind.FIELD, found?.kind)
        }
    }

    @Nested
    @DisplayName("Definition Location")
    inner class DefinitionLocationTests {

        @Test
        fun `findDefinition returns null for non-existent symbol`() {
            val location = index.findDefinition("non/existent#")
            assertNull(location)
        }

        @Test
        fun `findDefinition returns correct location for class`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val location = index.findDefinition("com/example/MyClass#")
            assertNotNull(location)
            assertEquals(testUri1.toString(), location?.uri)
            assertEquals(0, location?.range?.start?.line)
            assertEquals(0, location?.range?.start?.character)
            assertEquals(10, location?.range?.end?.line)
            assertEquals(1, location?.range?.end?.character)
        }

        @Test
        fun `findDefinition returns correct location for method`() {
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(5, 4, 7, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val doc = SemanticDocument(testUri1, listOf(methodSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val location = index.findDefinition("com/example/MyClass#myMethod().")
            assertNotNull(location)
            assertEquals(testUri1.toString(), location?.uri)
            assertEquals(5, location?.range?.start?.line)
            assertEquals(4, location?.range?.start?.character)
        }
    }

    @Nested
    @DisplayName("Reference Finding")
    inner class ReferenceFindingTests {

        @Test
        fun `findReferences returns empty list for non-existent symbol`() {
            val refs = index.findReferences("non/existent#")
            assertTrue(refs.isEmpty())
        }

        @Test
        fun `findReferences returns all occurrences across workspace`() {
            // Define a method in MyClass
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(5, 4, 7, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val methodDef = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(5, 4, 7, 5),
                role = OccurrenceRole.DEFINITION,
            )

            // Call it in the same file
            val call1 = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(10, 8, 10, 16),
                role = OccurrenceRole.CALL,
            )

            val doc1 = SemanticDocument(testUri1, listOf(methodSymbol), listOf(methodDef, call1))
            index.updateDocument(testUri1, doc1)

            // Call it from another file
            val call2 = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(3, 5, 3, 13),
                role = OccurrenceRole.CALL,
            )

            val doc2 = SemanticDocument(testUri3, emptyList(), listOf(call2))
            index.updateDocument(testUri3, doc2)

            val refs = index.findReferences("com/example/MyClass#myMethod().")
            assertEquals(3, refs.size)

            // Verify URIs
            val uris = refs.map { it.uri }.toSet()
            assertTrue(uris.contains(testUri1.toString()))
            assertTrue(uris.contains(testUri3.toString()))
        }

        @Test
        fun `findReferences includes definition occurrence`() {
            val fieldSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myField.",
                kind = SymbolKind.FIELD,
                range = Range(2, 4, 2, 15),
                name = "myField",
                owner = "com/example/MyClass#",
            )
            val fieldDef = SymbolOccurrence(
                symbol = "com/example/MyClass#myField.",
                range = Range(2, 4, 2, 15),
                role = OccurrenceRole.DEFINITION,
            )

            val doc = SemanticDocument(testUri1, listOf(fieldSymbol), listOf(fieldDef))
            index.updateDocument(testUri1, doc)

            val refs = index.findReferences("com/example/MyClass#myField.")
            assertEquals(1, refs.size)
            assertEquals(testUri1.toString(), refs[0].uri)
        }
    }

    @Nested
    @DisplayName("Class Hierarchy - Basic")
    inner class ClassHierarchyBasicTests {

        @Test
        fun `getSuperclass returns null for non-existent class`() {
            val superclass = index.getSuperclass("non/existent/MyClass")
            assertNull(superclass)
        }

        @Test
        fun `getSuperclass returns null when no hierarchy info available`() {
            // Note: Current SymbolInfo doesn't track inheritance, so this will return null
            // This is expected behavior until Phase 0 is extended
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val superclass = index.getSuperclass("com/example/MyClass")
            assertNull(superclass)
        }

        @Test
        fun `getInterfaces returns empty list for non-existent class`() {
            val interfaces = index.getInterfaces("non/existent/MyClass")
            assertTrue(interfaces.isEmpty())
        }

        @Test
        fun `getInterfaces returns empty list when no hierarchy info available`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val interfaces = index.getInterfaces("com/example/MyClass")
            assertTrue(interfaces.isEmpty())
        }

        @Test
        fun `getInheritanceChain returns empty list for non-existent class`() {
            val chain = index.getInheritanceChain("non/existent/MyClass")
            assertTrue(chain.isEmpty())
        }

        @Test
        fun `getInheritanceChain returns empty list when no hierarchy info available`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val chain = index.getInheritanceChain("com/example/MyClass")
            assertTrue(chain.isEmpty())
        }
    }

    @Nested
    @DisplayName("Member Lookup")
    inner class MemberLookupTests {

        @Test
        fun `findField returns null for non-existent class`() {
            val field = index.findField("non/existent/MyClass", "myField")
            assertNull(field)
        }

        @Test
        fun `findField returns field from class`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val fieldSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myField.",
                kind = SymbolKind.FIELD,
                range = Range(2, 4, 2, 15),
                name = "myField",
                owner = "com/example/MyClass#",
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol, fieldSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val field = index.findField("com/example/MyClass", "myField")
            assertNotNull(field)
            assertEquals("myField", field?.name)
            assertEquals(SymbolKind.FIELD, field?.kind)
        }

        @Test
        fun `findField returns null for non-existent field`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val field = index.findField("com/example/MyClass", "nonExistent")
            assertNull(field)
        }

        @Test
        fun `findMethod returns null for non-existent class`() {
            val method = index.findMethod("non/existent/MyClass", "myMethod")
            assertNull(method)
        }

        @Test
        fun `findMethod returns method from class without arity`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod(String,int).",
                kind = SymbolKind.METHOD,
                range = Range(5, 4, 7, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol, methodSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val method = index.findMethod("com/example/MyClass", "myMethod")
            assertNotNull(method)
            assertEquals("myMethod", method?.name)
            assertEquals(SymbolKind.METHOD, method?.kind)
        }

        @Test
        fun `findMethod returns method from class with matching arity`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val methodSymbol1 = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(5, 4, 7, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val methodSymbol2 = SymbolInfo(
                symbol = "com/example/MyClass#myMethod(String,int).",
                kind = SymbolKind.METHOD,
                range = Range(9, 4, 11, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol, methodSymbol1, methodSymbol2), emptyList())
            index.updateDocument(testUri1, doc)

            // Find no-arg version
            val method0 = index.findMethod("com/example/MyClass", "myMethod", 0)
            assertNotNull(method0)
            assertEquals("com/example/MyClass#myMethod().", method0?.symbolId)

            // Find 2-arg version
            val method2 = index.findMethod("com/example/MyClass", "myMethod", 2)
            assertNotNull(method2)
            assertEquals("com/example/MyClass#myMethod(String,int).", method2?.symbolId)
        }

        @Test
        fun `findMethod returns null for non-existent method`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val method = index.findMethod("com/example/MyClass", "nonExistent")
            assertNull(method)
        }

        @Test
        fun `getAllMembers returns empty list for non-existent class`() {
            val members = index.getAllMembers("non/existent/MyClass")
            assertTrue(members.isEmpty())
        }

        @Test
        fun `getAllMembers returns all class members`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val fieldSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myField.",
                kind = SymbolKind.FIELD,
                range = Range(2, 4, 2, 15),
                name = "myField",
                owner = "com/example/MyClass#",
            )
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(5, 4, 7, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val propertySymbol = SymbolInfo(
                symbol = "com/example/MyClass#myProperty.",
                kind = SymbolKind.PROPERTY,
                range = Range(3, 4, 3, 20),
                name = "myProperty",
                owner = "com/example/MyClass#",
            )
            val doc = SemanticDocument(
                testUri1,
                listOf(classSymbol, fieldSymbol, methodSymbol, propertySymbol),
                emptyList(),
            )
            index.updateDocument(testUri1, doc)

            val members = index.getAllMembers("com/example/MyClass")
            assertEquals(3, members.size)

            val memberNames = members.map { it.name }.toSet()
            assertTrue(memberNames.contains("myField"))
            assertTrue(memberNames.contains("myMethod"))
            assertTrue(memberNames.contains("myProperty"))
        }

        @Test
        fun `getAllMembers excludes class symbol itself`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val members = index.getAllMembers("com/example/MyClass")
            assertTrue(members.isEmpty())
        }

        @Test
        fun `getAllMembers with includeInherited=false returns only direct members`() {
            // Base class
            val baseSymbol = SymbolInfo(
                symbol = "com/example/BaseClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 5, 1),
                name = "BaseClass",
                owner = null,
            )
            val baseField = SymbolInfo(
                symbol = "com/example/BaseClass#baseField.",
                kind = SymbolKind.FIELD,
                range = Range(2, 4, 2, 20),
                name = "baseField",
                owner = "com/example/BaseClass#",
            )
            val doc1 = SemanticDocument(testUri1, listOf(baseSymbol, baseField), emptyList())
            index.updateDocument(testUri1, doc1)

            // Derived class (no inheritance info tracked yet, so this just tests the parameter works)
            val derivedSymbol = SymbolInfo(
                symbol = "com/example/DerivedClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 5, 1),
                name = "DerivedClass",
                owner = null,
            )
            val derivedField = SymbolInfo(
                symbol = "com/example/DerivedClass#derivedField.",
                kind = SymbolKind.FIELD,
                range = Range(2, 4, 2, 20),
                name = "derivedField",
                owner = "com/example/DerivedClass#",
            )
            val doc2 = SemanticDocument(testUri2, listOf(derivedSymbol, derivedField), emptyList())
            index.updateDocument(testUri2, doc2)

            val members = index.getAllMembers("com/example/DerivedClass", includeInherited = false)
            assertEquals(1, members.size)
            assertEquals("derivedField", members[0].name)
        }
    }

    @Nested
    @DisplayName("Incremental Updates")
    inner class IncrementalUpdateTests {

        @Test
        fun `updateDocument adds new document to index`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            val found = index.findSymbol("com/example/MyClass#")
            assertNotNull(found)
        }

        @Test
        fun `updateDocument replaces existing document in index`() {
            // Initial document
            val classSymbol1 = SymbolInfo(
                symbol = "com/example/OldClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "OldClass",
                owner = null,
            )
            val doc1 = SemanticDocument(testUri1, listOf(classSymbol1), emptyList())
            index.updateDocument(testUri1, doc1)

            // Replace with new document
            val classSymbol2 = SymbolInfo(
                symbol = "com/example/NewClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "NewClass",
                owner = null,
            )
            val doc2 = SemanticDocument(testUri1, listOf(classSymbol2), emptyList())
            index.updateDocument(testUri1, doc2)

            // Old should not be found
            val oldFound = index.findSymbol("com/example/OldClass#")
            assertNull(oldFound)

            // New should be found
            val newFound = index.findSymbol("com/example/NewClass#")
            assertNotNull(newFound)
        }

        @Test
        fun `removeDocument removes symbols from index`() {
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri1, listOf(classSymbol), emptyList())
            index.updateDocument(testUri1, doc)

            index.removeDocument(testUri1)

            val found = index.findSymbol("com/example/MyClass#")
            assertNull(found)
        }

        @Test
        fun `removeDocument removes references from index`() {
            val occurrence = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(5, 0, 5, 10),
                role = OccurrenceRole.CALL,
            )
            val doc = SemanticDocument(testUri1, emptyList(), listOf(occurrence))
            index.updateDocument(testUri1, doc)

            index.removeDocument(testUri1)

            val refs = index.findReferences("com/example/MyClass#myMethod().")
            assertTrue(refs.isEmpty())
        }

        @Test
        fun `updateDocument maintains references from other files`() {
            // Define method in file 1
            val methodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(5, 4, 7, 5),
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val doc1 = SemanticDocument(testUri1, listOf(methodSymbol), emptyList())
            index.updateDocument(testUri1, doc1)

            // Reference in file 2
            val call = SymbolOccurrence(
                symbol = "com/example/MyClass#myMethod().",
                range = Range(3, 5, 3, 13),
                role = OccurrenceRole.CALL,
            )
            val doc2 = SemanticDocument(testUri3, emptyList(), listOf(call))
            index.updateDocument(testUri3, doc2)

            // Update file 1 (should not affect file 2's reference)
            val updatedMethodSymbol = SymbolInfo(
                symbol = "com/example/MyClass#myMethod().",
                kind = SymbolKind.METHOD,
                range = Range(6, 4, 8, 5), // Different range
                name = "myMethod",
                owner = "com/example/MyClass#",
            )
            val updatedDoc1 = SemanticDocument(testUri1, listOf(updatedMethodSymbol), emptyList())
            index.updateDocument(testUri1, updatedDoc1)

            val refs = index.findReferences("com/example/MyClass#myMethod().")
            assertEquals(1, refs.size)
            assertEquals(testUri3.toString(), refs[0].uri)
        }
    }

    @Nested
    @DisplayName("Symbol ID Parsing")
    inner class SymbolIdParsingTests {

        @Test
        fun `extractClassFqn extracts class FQN from class symbol ID`() {
            val fqn = index.extractClassFqn("com/example/MyClass#")
            assertEquals("com/example/MyClass", fqn)
        }

        @Test
        fun `extractClassFqn extracts class FQN from method symbol ID`() {
            val fqn = index.extractClassFqn("com/example/MyClass#myMethod(String).")
            assertEquals("com/example/MyClass", fqn)
        }

        @Test
        fun `extractClassFqn extracts class FQN from field symbol ID`() {
            val fqn = index.extractClassFqn("com/example/MyClass#myField.")
            assertEquals("com/example/MyClass", fqn)
        }

        @Test
        fun `extractClassFqn handles nested classes`() {
            val fqn = index.extractClassFqn("com/example/Outer\$Inner#")
            assertEquals("com/example/Outer\$Inner", fqn)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `handles empty workspace`() {
            val found = index.findSymbol("any/symbol#")
            assertNull(found)

            val refs = index.findReferences("any/symbol#")
            assertTrue(refs.isEmpty())
        }

        @Test
        fun `handles document with no symbols`() {
            val doc = SemanticDocument(testUri1, emptyList(), emptyList())
            index.updateDocument(testUri1, doc)

            val found = index.findSymbol("any/symbol#")
            assertNull(found)
        }

        @Test
        fun `handles duplicate symbol across files`() {
            // Same class defined in two files (shouldn't happen but handle gracefully)
            val symbol1 = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc1 = SemanticDocument(testUri1, listOf(symbol1), emptyList())
            index.updateDocument(testUri1, doc1)

            val symbol2 = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc2 = SemanticDocument(testUri2, listOf(symbol2), emptyList())
            index.updateDocument(testUri2, doc2)

            // Should return first definition (or any, doesn't matter which)
            val found = index.findSymbol("com/example/MyClass#")
            assertNotNull(found)
            assertEquals("MyClass", found?.name)
        }

        @Test
        fun `handles removeDocument for non-existent document`() {
            // Should not throw
            index.removeDocument(testUri1)

            // Verify index is still functional
            val classSymbol = SymbolInfo(
                symbol = "com/example/MyClass#",
                kind = SymbolKind.CLASS,
                range = Range(0, 0, 10, 1),
                name = "MyClass",
                owner = null,
            )
            val doc = SemanticDocument(testUri2, listOf(classSymbol), emptyList())
            index.updateDocument(testUri2, doc)

            val found = index.findSymbol("com/example/MyClass#")
            assertNotNull(found)
        }
    }
}
