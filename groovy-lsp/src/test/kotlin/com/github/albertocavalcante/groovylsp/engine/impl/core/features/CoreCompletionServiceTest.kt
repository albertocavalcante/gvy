package com.github.albertocavalcante.groovylsp.engine.impl.core.features

import com.github.albertocavalcante.groovylsp.engine.adapters.ParseUnit
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNode
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedNodeKind
import com.github.albertocavalcante.groovylsp.engine.adapters.UnifiedSymbol
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [CoreCompletionService].
 */
class CoreCompletionServiceTest {

    private lateinit var parser: GroovyParser

    @BeforeEach
    fun setup() {
        parser = GroovyParser(ParserConfiguration())
    }

    @Test
    fun `getCompletions returns non-empty list for keyword completion`(): Unit = runBlocking {
        val service = CoreCompletionService()
        val params = CompletionParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 0))
        val parseUnit = createParseUnit("", "file:///test.groovy")

        val result = service.getCompletions(params, parseUnit, "")

        assertTrue(result.isLeft)
        assertFalse(result.left.isEmpty())
    }

    @Test
    fun `getCompletions includes Groovy keywords`(): Unit = runBlocking {
        val service = CoreCompletionService()
        val params = CompletionParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 0))
        val parseUnit = createParseUnit("", "file:///test.groovy")

        val result = service.getCompletions(params, parseUnit, "")

        assertTrue(result.isLeft)
        val labels = result.left.map { it.label }
        assertTrue(labels.contains("def"))
        assertTrue(labels.contains("class"))
        assertTrue(labels.contains("if"))
    }

    @Test
    fun `getCompletions works when parseUnit is null`(): Unit = runBlocking {
        val service = CoreCompletionService()
        val params = CompletionParams(TextDocumentIdentifier("file:///test.groovy"), Position(0, 0))

        val result = service.getCompletions(params, null, "")

        assertTrue(result.isLeft)
        // Should still return basic completions even without context
        assertFalse(result.left.isEmpty())
    }

    @Test
    fun `getCompletions includes symbols from context`(): Unit = runBlocking {
        val service = CoreCompletionService()
        val params = CompletionParams(TextDocumentIdentifier("file:///test.groovy"), Position(2, 0))

        // Create a mock ParseUnit with some symbols
        val parseUnit = object : ParseUnit {
            override val uri: String = "file:///test.groovy"
            override val isSuccessful: Boolean = true
            override val diagnostics: List<Diagnostic> = emptyList()
            override fun nodeAt(position: Position): UnifiedNode? = null
            override fun allSymbols(): List<UnifiedSymbol> = listOf(
                UnifiedSymbol(
                    "myVariable",
                    UnifiedNodeKind.VARIABLE,
                    Range(Position(0, 0), Position(0, 10)),
                    Range(Position(0, 0), Position(0, 10)),
                ),
                UnifiedSymbol(
                    "myMethod",
                    UnifiedNodeKind.METHOD,
                    Range(Position(1, 0), Position(1, 10)),
                    Range(Position(1, 0), Position(1, 10)),
                ),
            )
        }

        val result = service.getCompletions(params, parseUnit, "")

        assertTrue(result.isLeft)
        val labels = result.left.map { it.label }
        val kinds = result.left.associate { it.label to it.kind }

        assertTrue(labels.contains("myVariable"))
        assertTrue(labels.contains("myMethod"))

        // Verify kinds mapping
        // Need to import CompletionItemKind
        assertTrue(kinds["myVariable"] == org.eclipse.lsp4j.CompletionItemKind.Variable)
        assertTrue(kinds["myMethod"] == org.eclipse.lsp4j.CompletionItemKind.Method)
    }

    // Helper to create a ParseUnit from code
    private fun createParseUnit(code: String, uri: String): ParseUnit {
        val cu = parser.parse(code).result.orElse(null)
        return object : ParseUnit {
            override val uri: String = uri
            override val isSuccessful: Boolean = cu != null
            override val diagnostics: List<Diagnostic> = emptyList()
            override fun nodeAt(position: Position): UnifiedNode? = null
            override fun allSymbols(): List<UnifiedSymbol> = emptyList()
        }
    }
}
