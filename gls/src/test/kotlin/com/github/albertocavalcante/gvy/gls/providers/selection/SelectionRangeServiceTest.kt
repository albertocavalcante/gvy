package com.github.albertocavalcante.gvy.gls.providers.selection

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerCapabilitiesFactory
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelectionRangeServiceTest {
    private val sourceCases = listOf(
        "def unfinished = {\n    fresh +\n",
        "// fresh comment\n",
        "def text = \"fresh\"\n",
        "def fresh = java.util.Map.Entry.class\nprintln fresh\n",
        "[1, 2].collect { fresh -> fresh + 1 }\n",
        "pipeline { stages { stage('Build') { steps { echo 'fresh' } } } }\n",
        """
            class OfflineSpec extends spock.lang.Specification {
                def "works"() {
                    given:
                    def fresh = 1
                    expect:
                    fresh + 1 == 2
                }
            }
        """.trimIndent() + "\n",
        """
            class Transformed {
                @groovy.transform.Memoized
                def call(fresh) { fresh + 1 }
            }
        """.trimIndent() + "\n",
    )

    @Test
    fun `selection ranges are advertised through the public capability factory`() {
        assertEquals(true, ServerCapabilitiesFactory.createInitializeResult().capabilities.selectionRangeProvider?.left)
    }

    @ParameterizedTest(name = "line ending case {index}")
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `public selection preserves batch order and grows from identifier to expression and document`(
        lineEnding: String,
    ): Unit = runBlocking {
        val source = """
            class Sample {
                def next(value) {
                    return value + 1
                }
            }
        """.trimIndent().replace("\n", lineEnding) + lineEnding
        val uri = URI.create("file:///Selection.groovy")
        val positions = listOf(Position(2, 16), Position(1, 14))
        val expected = listOf(
            Range(Position(2, 15), Position(2, 20)),
            Range(Position(1, 13), Position(1, 18)),
        )
        val documents = DocumentProvider().apply { put(uri, source) }
        val compilation = GroovyCompilationService()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            compilation.compile(uri, source)
            val service = GroovyTextDocumentService(
                coroutineScope = scope,
                compilationService = compilation,
                options = GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val params = SelectionRangeParams().apply {
                textDocument = TextDocumentIdentifier(uri.toString())
                this.positions = positions
            }
            val results = assertNotNull(service.selectionRange(params).get(5, TimeUnit.SECONDS))
            assertEquals(positions.size, results.size)
            for ((result, leaf) in results.zip(expected)) {
                assertEquals(leaf, result.range)
                val chain = chain(result)
                assertEquals(Range(Position(0, 0), Position(5, 0)), chain.last())
                for ((child, parent) in chain.zipWithNext()) {
                    assertTrue(contains(parent, child.start) && contains(parent, child.end))
                    assertTrue(parent != child)
                }
            }
            assertTrue(chain(results.first()).contains(Range(Position(2, 15), Position(2, 24))))
        } finally {
            scope.cancel()
            compilation.clearCaches()
        }
    }

    @Test
    fun `partial code scripts and transformed classes retain safe source selections`(): Unit = runBlocking {
        for (source in sourceCases) {
            val uri = URI.create("file:///SelectionPartial.groovy")
            val index = TextIndex(source)
            val offset = source.indexOf("fresh")
            val documents = DocumentProvider().apply { put(uri, source, 7) }
            val compilation = GroovyCompilationService()
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            try {
                compilation.compile(uri, source)
                val service = GroovyTextDocumentService(
                    coroutineScope = scope,
                    compilationService = compilation,
                    options = GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
                val params = SelectionRangeParams().apply {
                    textDocument = TextDocumentIdentifier(uri.toString())
                    positions = listOf(index.positionOf(offset + 1))
                }
                val selected = assertNotNull(service.selectionRange(params).get(5, TimeUnit.SECONDS)).single()
                assertEquals(Range(index.positionOf(offset), index.positionOf(offset + 5)), selected.range)
                assertEquals(Range(Position(0, 0), index.positionOf(source.length)), chain(selected).last())
            } finally {
                scope.cancel()
                compilation.clearCaches()
            }
        }
    }

    private fun chain(first: SelectionRange): List<Range> {
        val ranges = mutableListOf<Range>()
        var current: SelectionRange? = first
        while (current != null) {
            assertTrue(ranges.size < 100, "Selection parent chains must terminate")
            ranges += current.range
            current = current.parent
        }
        return ranges
    }

    private fun contains(range: Range, position: Position): Boolean =
        compare(range.start, position) <= 0 && compare(position, range.end) <= 0

    private fun compare(left: Position, right: Position): Int =
        compareValuesBy(left, right, Position::getLine, Position::getCharacter)
}
