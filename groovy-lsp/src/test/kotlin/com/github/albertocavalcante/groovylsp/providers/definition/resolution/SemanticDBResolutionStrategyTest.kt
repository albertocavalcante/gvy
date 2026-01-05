package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class SemanticDBResolutionStrategyTest {

    private lateinit var semanticDb: GroovySemanticDB
    private lateinit var workspaceSymbolIndex: WorkspaceSymbolIndex
    private lateinit var strategy: SemanticDBResolutionStrategy

    // Mock URIs for testing
    private val sourceUri = URI("file:///test/Source.groovy")
    private val targetUri = URI("file:///test/Target.groovy")

    // Helper to create a dummy ClassNode for testing
    private fun createDummyClassNode() = ClassNode("DummyClass", 0, null)

    @BeforeEach
    fun setup() {
        semanticDb = GroovySemanticDB()
        workspaceSymbolIndex = WorkspaceSymbolIndex(semanticDb)
        strategy = SemanticDBResolutionStrategy(workspaceSymbolIndex)
    }

    @Test
    fun `should resolve cross-file method definition`() = runBlocking {
        // Given: Target class with a method
        val targetDoc = SemanticDocument(
            uri = targetUri,
            symbols = listOf(
                SymbolInfo(
                    symbol = "test/Target#",
                    kind = SymbolKind.CLASS,
                    range = Range(0, 0, 0, 12),
                    name = "Target",
                    owner = null,
                ),
                SymbolInfo(
                    symbol = "test/Target#doSomething().",
                    kind = SymbolKind.METHOD,
                    range = Range(1, 4, 1, 16),
                    name = "doSomething",
                    owner = "test/Target#",
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = "test/Target#doSomething().",
                    range = Range(1, 4, 1, 16),
                    role = OccurrenceRole.DEFINITION,
                ),
            ),
        )
        semanticDb.updateDocument(targetUri, targetDoc)

        // And: Source file with a method call
        val sourceDoc = SemanticDocument(
            uri = sourceUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = "test/Target#doSomething().",
                    range = Range(5, 8, 5, 20),
                    role = OccurrenceRole.CALL,
                ),
            ),
        )
        semanticDb.updateDocument(sourceUri, sourceDoc)

        // When: Resolving at the call site
        val context = ResolutionContext(
            targetNode = createDummyClassNode(),
            documentUri = sourceUri,
            position = Position(5, 10),
        )
        val result = strategy.resolve(context)

        // Then: Should resolve to the method definition
        result.fold(
            ifLeft = { error -> throw AssertionError("Expected Right, got Left: ${error.source} - ${error.reason}") },
            ifRight = { definitionResult ->
                assertInstanceOf(DefinitionResolver.DefinitionResult.Binary::class.java, definitionResult)
                val binaryResult = definitionResult as DefinitionResolver.DefinitionResult.Binary
                assertEquals(targetUri.toString(), binaryResult.uri.toString())
                assertEquals("doSomething", binaryResult.name)
            },
        )
    }

    @Test
    fun `should resolve cross-file field definition`() = runBlocking {
        // Given: Target class with a field
        val targetDoc = SemanticDocument(
            uri = targetUri,
            symbols = listOf(
                SymbolInfo(
                    symbol = "test/Target#",
                    kind = SymbolKind.CLASS,
                    range = Range(0, 0, 0, 12),
                    name = "Target",
                    owner = null,
                ),
                SymbolInfo(
                    symbol = "test/Target#myField.",
                    kind = SymbolKind.FIELD,
                    range = Range(1, 4, 1, 11),
                    name = "myField",
                    owner = "test/Target#",
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = "test/Target#myField.",
                    range = Range(1, 4, 1, 11),
                    role = OccurrenceRole.DEFINITION,
                ),
            ),
        )
        semanticDb.updateDocument(targetUri, targetDoc)

        // And: Source file with a field reference
        val sourceDoc = SemanticDocument(
            uri = sourceUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = "test/Target#myField.",
                    range = Range(3, 10, 3, 17),
                    role = OccurrenceRole.REFERENCE,
                ),
            ),
        )
        semanticDb.updateDocument(sourceUri, sourceDoc)

        // When: Resolving at the field reference
        val context = ResolutionContext(
            targetNode = createDummyClassNode(),
            documentUri = sourceUri,
            position = Position(3, 12),
        )
        val result = strategy.resolve(context)

        // Then: Should resolve to the field definition
        result.fold(
            ifLeft = { error -> throw AssertionError("Expected Right, got Left: ${error.source} - ${error.reason}") },
            ifRight = { definitionResult ->
                assertInstanceOf(DefinitionResolver.DefinitionResult.Binary::class.java, definitionResult)
                val binaryResult = definitionResult as DefinitionResolver.DefinitionResult.Binary
                assertEquals(targetUri.toString(), binaryResult.uri.toString())
                assertEquals("myField", binaryResult.name)
            },
        )
    }

    @Test
    fun `should return notApplicable when document not in SemanticDB`() = runBlocking {
        // Given: No document in SemanticDB for the source URI
        val context = ResolutionContext(
            targetNode = createDummyClassNode(),
            documentUri = sourceUri,
            position = Position(5, 10),
        )

        // When: Resolving
        val result = strategy.resolve(context)

        // Then: Should return notApplicable (Left)
        assertTrue(result.isLeft(), "Expected Left (error), got Right")
        result.fold(
            ifLeft = { error -> assertEquals("SemanticDB", error.source) },
            ifRight = { throw AssertionError("Expected Left, got Right") },
        )
    }

    @Test
    fun `should return notApplicable when no occurrence at position`() = runBlocking {
        // Given: Document exists but no occurrence at the cursor position
        val sourceDoc = SemanticDocument(
            uri = sourceUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = "test/Target#method().",
                    range = Range(5, 8, 5, 20),
                    role = OccurrenceRole.CALL,
                ),
            ),
        )
        semanticDb.updateDocument(sourceUri, sourceDoc)

        val context = ResolutionContext(
            targetNode = createDummyClassNode(),
            documentUri = sourceUri,
            position = Position(10, 5), // Different line
        )

        // When: Resolving
        val result = strategy.resolve(context)

        // Then: Should return notApplicable
        assertTrue(result.isLeft(), "Expected Left (error), got Right")
        result.fold(
            ifLeft = { error -> assertEquals("SemanticDB", error.source) },
            ifRight = { throw AssertionError("Expected Left, got Right") },
        )
    }

    @Test
    fun `should return notFound when symbol not in workspace index`() = runBlocking {
        // Given: Occurrence exists but symbol not defined anywhere
        val sourceDoc = SemanticDocument(
            uri = sourceUri,
            symbols = emptyList(),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = "test/Unknown#method().",
                    range = Range(5, 8, 5, 20),
                    role = OccurrenceRole.CALL,
                ),
            ),
        )
        semanticDb.updateDocument(sourceUri, sourceDoc)

        val context = ResolutionContext(
            targetNode = createDummyClassNode(),
            documentUri = sourceUri,
            position = Position(5, 10),
        )

        // When: Resolving
        val result = strategy.resolve(context)

        // Then: Should return notFound
        assertTrue(result.isLeft(), "Expected Left (error), got Right")
        result.fold(
            ifLeft = { error ->
                assertEquals("SemanticDB", error.source)
                assertEquals("Symbol not found in workspace index", error.reason)
            },
            ifRight = { throw AssertionError("Expected Left, got Right") },
        )
    }

    @Test
    fun `should handle same-file resolution`() = runBlocking {
        // Given: Both definition and reference in same file
        val sourceDoc = SemanticDocument(
            uri = sourceUri,
            symbols = listOf(
                SymbolInfo(
                    symbol = "test/Source#",
                    kind = SymbolKind.CLASS,
                    range = Range(0, 0, 0, 12),
                    name = "Source",
                    owner = null,
                ),
                SymbolInfo(
                    symbol = "test/Source#localMethod().",
                    kind = SymbolKind.METHOD,
                    range = Range(2, 4, 2, 15),
                    name = "localMethod",
                    owner = "test/Source#",
                ),
            ),
            occurrences = listOf(
                SymbolOccurrence(
                    symbol = "test/Source#localMethod().",
                    range = Range(2, 4, 2, 15),
                    role = OccurrenceRole.DEFINITION,
                ),
                SymbolOccurrence(
                    symbol = "test/Source#localMethod().",
                    range = Range(5, 8, 5, 19),
                    role = OccurrenceRole.CALL,
                ),
            ),
        )
        semanticDb.updateDocument(sourceUri, sourceDoc)

        val context = ResolutionContext(
            targetNode = createDummyClassNode(),
            documentUri = sourceUri,
            position = Position(5, 10),
        )

        // When: Resolving
        val result = strategy.resolve(context)

        // Then: Should resolve to the local method definition
        result.fold(
            ifLeft = { error -> throw AssertionError("Expected Right, got Left: ${error.source} - ${error.reason}") },
            ifRight = { definitionResult ->
                assertInstanceOf(DefinitionResolver.DefinitionResult.Binary::class.java, definitionResult)
                val binaryResult = definitionResult as DefinitionResolver.DefinitionResult.Binary
                assertEquals(sourceUri.toString(), binaryResult.uri.toString())
                assertEquals("localMethod", binaryResult.name)
            },
        )
    }
}
