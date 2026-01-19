package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for cross-file reference finding using WorkspaceSymbolIndex.
 */
class CrossFileReferenceProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var semanticDb: GroovySemanticDB
    private lateinit var workspaceIndex: WorkspaceSymbolIndex
    private lateinit var referenceProvider: ReferenceProvider

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        semanticDb = GroovySemanticDB()
        workspaceIndex = WorkspaceSymbolIndex(semanticDb)
        referenceProvider = ReferenceProvider(compilationService, workspaceIndex)
    }

    @Test
    fun `test find references to method defined in another file`() = runTest {
        // Arrange - Create two files: one with method definition, one with method call
        val defUri = URI.create("file:///ServiceClass.groovy")
        val refUri = URI.create("file:///ClientClass.groovy")

        // File 1: ServiceClass with method definition
        val defContent = """
            class ServiceClass {
                def helperMethod() {
                    return "helper"
                }
            }
        """.trimIndent()

        // File 2: ClientClass calling the method
        val refContent = """
            def service = new ServiceClass()
            def result = service.helperMethod()
        """.trimIndent()

        // Compile both files
        compilationService.compile(defUri, defContent)
        compilationService.compile(refUri, refContent)

        // Create semantic documents
        val methodSymbolId = "ServiceClass#helperMethod()."

        // Definition file document
        val defDoc = SemanticDocument(
            uri = defUri,
            symbols = listOf(
                SymbolInfo(
                    symbol = "ServiceClass#",
                    kind = SymbolKind.CLASS,
                    range = Range(1, 6, 1, 18),
                    name = "ServiceClass",
                    owner = null,
                ),
                SymbolInfo(
                    symbol = methodSymbolId,
                    kind = SymbolKind.METHOD,
                    range = Range(2, 8, 2, 20),
                    name = "helperMethod",
                    owner = "ServiceClass#",
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = methodSymbolId,
                    range = Range(2, 8, 2, 20),
                    role = OccurrenceRole.DEFINITION,
                ),
            ),
        )

        // Reference file document
        val refDoc = SemanticDocument(
            uri = refUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = methodSymbolId,
                    range = Range(1, 25, 1, 37),
                    role = OccurrenceRole.CALL,
                ),
            ),
        )

        // Update workspace index
        workspaceIndex.updateDocument(defUri, defDoc)
        workspaceIndex.updateDocument(refUri, refDoc)

        // Act - Find references from the definition location
        val references = referenceProvider.provideReferences(
            defUri.toString(),
            Position(2, 10), // in "helperMethod" definition
            includeDeclaration = true,
        ).toList()

        // Assert - Should find both definition and call
        assertTrue(references.size >= 2, "Should find at least definition + call across files")

        val uris = references.map { it.uri }.toSet()
        assertTrue(uris.contains(defUri.toString()), "Should include definition file")
        assertTrue(uris.contains(refUri.toString()), "Should include reference file")
    }

    @Test
    fun `test find references to field defined in another file`() = runTest {
        // Arrange - Create two files: one with field definition, one with field access
        val defUri = URI.create("file:///DataClass.groovy")
        val refUri = URI.create("file:///AccessorClass.groovy")

        val defContent = """
            class DataClass {
                def myField = "data"
            }
        """.trimIndent()

        val refContent = """
            def data = new DataClass()
            println data.myField
        """.trimIndent()

        // Compile both files
        compilationService.compile(defUri, defContent)
        compilationService.compile(refUri, refContent)

        // Create semantic documents
        val fieldSymbolId = "DataClass#myField."

        val defDoc = SemanticDocument(
            uri = defUri,
            symbols = listOf(
                SymbolInfo(
                    symbol = "DataClass#",
                    kind = SymbolKind.CLASS,
                    range = Range(1, 6, 1, 15),
                    name = "DataClass",
                    owner = null,
                ),
                SymbolInfo(
                    symbol = fieldSymbolId,
                    kind = SymbolKind.FIELD,
                    range = Range(2, 8, 2, 15),
                    name = "myField",
                    owner = "DataClass#",
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = fieldSymbolId,
                    range = Range(2, 8, 2, 15),
                    role = OccurrenceRole.DEFINITION,
                ),
            ),
        )

        val refDoc = SemanticDocument(
            uri = refUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = fieldSymbolId,
                    range = Range(1, 13, 1, 20),
                    role = OccurrenceRole.REFERENCE,
                ),
            ),
        )

        workspaceIndex.updateDocument(defUri, defDoc)
        workspaceIndex.updateDocument(refUri, refDoc)

        // Act - Find references from the field definition
        val references = referenceProvider.provideReferences(
            defUri.toString(),
            Position(2, 10), // in "myField" definition
            includeDeclaration = true,
        ).toList()

        // Assert - Should find both definition and reference
        assertTrue(references.size >= 2, "Should find at least definition + reference across files")

        val uris = references.map { it.uri }.toSet()
        assertTrue(uris.contains(defUri.toString()), "Should include definition file")
        assertTrue(uris.contains(refUri.toString()), "Should include reference file")
    }

    @Test
    fun `test find references to class defined in another file`() = runTest {
        // Arrange - Create two files: one with class definition, one with class usage
        val defUri = URI.create("file:///MyClass.groovy")
        val refUri = URI.create("file:///Usage.groovy")

        val defContent = """
            class MyClass {
                def method() {}
            }
        """.trimIndent()

        val refContent = """
            def instance = new MyClass()
            MyClass another = null
        """.trimIndent()

        // Compile both files
        compilationService.compile(defUri, defContent)
        compilationService.compile(refUri, refContent)

        // Create semantic documents
        val classSymbolId = "MyClass#"

        val defDoc = SemanticDocument(
            uri = defUri,
            symbols = listOf(
                SymbolInfo(
                    symbol = classSymbolId,
                    kind = SymbolKind.CLASS,
                    range = Range(1, 6, 1, 13),
                    name = "MyClass",
                    owner = null,
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = classSymbolId,
                    range = Range(1, 6, 1, 13),
                    role = OccurrenceRole.DEFINITION,
                ),
            ),
        )

        val refDoc = SemanticDocument(
            uri = refUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = classSymbolId,
                    range = Range(1, 19, 1, 26),
                    role = OccurrenceRole.TYPE_REF,
                ),
                SymbolOccurrence(
                    symbol = classSymbolId,
                    range = Range(2, 0, 2, 7),
                    role = OccurrenceRole.TYPE_REF,
                ),
            ),
        )

        workspaceIndex.updateDocument(defUri, defDoc)
        workspaceIndex.updateDocument(refUri, refDoc)

        // Act - Find references from the class definition
        val references = referenceProvider.provideReferences(
            defUri.toString(),
            Position(1, 8), // in "MyClass" definition
            includeDeclaration = true,
        ).toList()

        // Assert - Should find definition + 2 references
        assertTrue(references.size >= 3, "Should find at least definition + 2 references across files")

        val uris = references.map { it.uri }.toSet()
        assertTrue(uris.contains(defUri.toString()), "Should include definition file")
        assertTrue(uris.contains(refUri.toString()), "Should include reference file")
    }

    @Test
    fun `test exclude declaration when includeDeclaration is false`() = runTest {
        // Arrange
        val defUri = URI.create("file:///Definition.groovy")
        val refUri = URI.create("file:///Reference.groovy")

        val defContent = "def myMethod() { return 'test' }"
        val refContent = "myMethod()"

        compilationService.compile(defUri, defContent)
        compilationService.compile(refUri, refContent)

        val methodSymbolId = "myMethod()."

        val defDoc = SemanticDocument(
            uri = defUri,
            symbols = listOf(
                SymbolInfo(
                    symbol = methodSymbolId,
                    kind = SymbolKind.METHOD,
                    range = Range(0, 4, 0, 12),
                    name = "myMethod",
                    owner = null,
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = methodSymbolId,
                    range = Range(0, 4, 0, 12),
                    role = OccurrenceRole.DEFINITION,
                ),
            ),
        )

        val refDoc = SemanticDocument(
            uri = refUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = methodSymbolId,
                    range = Range(0, 0, 0, 8),
                    role = OccurrenceRole.CALL,
                ),
            ),
        )

        workspaceIndex.updateDocument(defUri, defDoc)
        workspaceIndex.updateDocument(refUri, refDoc)

        // Act - Find references WITHOUT declaration
        val references = referenceProvider.provideReferences(
            defUri.toString(),
            Position(0, 6), // in "myMethod" definition
            includeDeclaration = false,
        ).toList()

        // Assert - Should find only the call, not the definition
        assertTrue(references.isNotEmpty(), "Should find at least the call")
        references.forEach { location ->
            // Should not include the definition location
            if (location.uri == defUri.toString()) {
                val range = location.range
                assertTrue(
                    range.start.line != 0 || range.start.character != 4,
                    "Should not include definition location when includeDeclaration is false",
                )
            }
        }
    }

    @Test
    fun `test deduplication between AST and workspace index results`() = runTest {
        // Arrange - Create a file with both AST analysis and SemanticDB index
        val uri = URI.create("file:///SameFile.groovy")

        val content = """
            def localVar = "test"
            println localVar
            def result = localVar + " suffix"
        """.trimIndent()

        compilationService.compile(uri, content)

        // Create semantic document with same references that AST will find
        val varSymbolId = "localVar."

        val doc = SemanticDocument(
            uri = uri,
            symbols = listOf(
                SymbolInfo(
                    symbol = varSymbolId,
                    kind = SymbolKind.VARIABLE,
                    range = Range(0, 4, 0, 12),
                    name = "localVar",
                    owner = null,
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = varSymbolId,
                    range = Range(0, 4, 0, 12),
                    role = OccurrenceRole.DEFINITION,
                ),
                SymbolOccurrence(
                    symbol = varSymbolId,
                    range = Range(1, 8, 1, 16),
                    role = OccurrenceRole.REFERENCE,
                ),
                SymbolOccurrence(
                    symbol = varSymbolId,
                    range = Range(2, 13, 2, 21),
                    role = OccurrenceRole.REFERENCE,
                ),
            ),
        )

        workspaceIndex.updateDocument(uri, doc)

        // Act - Find references (both AST and workspace index will contribute)
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 6), // in "localVar" declaration
            includeDeclaration = true,
        ).toList()

        // Assert - Should deduplicate, so we get exactly 3 locations (not 6)
        assertEquals(3, references.size, "Should deduplicate same locations from AST and workspace index")

        // Verify no duplicate locations
        val locationKeys = references.map { loc ->
            "${loc.uri}:${loc.range.start.line}:${loc.range.start.character}"
        }
        assertEquals(locationKeys.size, locationKeys.toSet().size, "All locations should be unique")
    }

    @Test
    fun `test fallback to same-file when workspace index unavailable`() = runTest {
        // Arrange - Create provider WITHOUT workspace index
        val providerWithoutIndex = ReferenceProvider(compilationService, null)

        val uri = URI.create("file:///test.groovy")
        val content = """
            def localVar = "test"
            println localVar
            def result = localVar + " suffix"
        """.trimIndent()

        compilationService.compile(uri, content)

        // Act - Find references (should work with AST only)
        val references = providerWithoutIndex.provideReferences(
            uri.toString(),
            Position(0, 6),
            includeDeclaration = true,
        ).toList()

        // Assert - Should still find references using AST analysis
        assertTrue(references.isNotEmpty(), "Should find references using AST when workspace index unavailable")
        assertEquals(3, references.size, "Should find all same-file references")
    }

    @Test
    fun `test workspace index returns empty when symbol not found`() = runTest {
        // Arrange - Create semantic document without the symbol we're looking for
        val uri = URI.create("file:///test.groovy")
        val content = """
            def localVar = "test"
            println localVar
        """.trimIndent()

        compilationService.compile(uri, content)

        // Create semantic document with different symbol
        val doc = SemanticDocument(
            uri = uri,
            symbols = listOf(
                SymbolInfo(
                    symbol = "otherSymbol.",
                    kind = SymbolKind.VARIABLE,
                    range = Range(0, 4, 0, 12),
                    name = "otherSymbol",
                    owner = null,
                ),
            ),
            occurrences = emptyList(), // No occurrences for localVar
        )

        workspaceIndex.updateDocument(uri, doc)

        // Act - Try to find references at a position that has no occurrence in SemanticDB
        val references = referenceProvider.provideReferences(
            uri.toString(),
            Position(0, 6), // in "localVar" but not in semantic DB
            includeDeclaration = true,
        ).toList()

        // Assert - Should still find references from AST analysis
        // Even though workspace index has nothing, AST should still work
        assertTrue(references.isNotEmpty(), "Should find references from AST even when not in workspace index")
    }

    @Test
    fun `test position matching with occurrence range`() = runTest {
        // Arrange - Test that position matching works correctly with SemanticDB ranges
        val uri = URI.create("file:///test.groovy")
        val content = """
            class TestClass {
                def myMethod() {
                    return "test"
                }
            }
        """.trimIndent()

        compilationService.compile(uri, content)

        val methodSymbolId = "TestClass#myMethod()."

        val doc = SemanticDocument(
            uri = uri,
            symbols = listOf(
                SymbolInfo(
                    symbol = "TestClass#",
                    kind = SymbolKind.CLASS,
                    range = Range(1, 6, 1, 15),
                    name = "TestClass",
                    owner = null,
                ),
                SymbolInfo(
                    symbol = methodSymbolId,
                    kind = SymbolKind.METHOD,
                    range = Range(2, 8, 2, 16),
                    name = "myMethod",
                    owner = "TestClass#",
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = methodSymbolId,
                    range = Range(2, 8, 2, 16), // "myMethod" at line 2
                    role = OccurrenceRole.DEFINITION,
                ),
            ),
        )

        workspaceIndex.updateDocument(uri, doc)

        // Act - Find references at different positions within the method name range
        val referencesAtStart = referenceProvider.provideReferences(
            uri.toString(),
            Position(2, 8), // Start of "myMethod"
            includeDeclaration = true,
        ).toList()

        val referencesAtMiddle = referenceProvider.provideReferences(
            uri.toString(),
            Position(2, 12), // Middle of "myMethod"
            includeDeclaration = true,
        ).toList()

        // Assert - Both positions should find the same symbol
        assertTrue(referencesAtStart.isNotEmpty(), "Should find references at range start")
        assertTrue(referencesAtMiddle.isNotEmpty(), "Should find references at range middle")
    }
}
