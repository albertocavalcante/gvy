package com.github.albertocavalcante.groovylsp.providers

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * TDD tests for SignatureHelpProvider receiver type resolution using SemanticTypeResolver.
 * These tests ensure the migration from TypeInferencer to SemanticTypeResolver maintains correctness.
 */
class SignatureHelpReceiverTypeTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var documentProvider: DocumentProvider
    private lateinit var signatureHelpProvider: SignatureHelpProvider
    private lateinit var semanticResolver: SemanticTypeResolver

    @BeforeEach
    fun setup() {
        compilationService = GroovyCompilationService()
        documentProvider = DocumentProvider()
        semanticResolver = SemanticTypeResolver(compilationService.classpathService.getTypeSolver())
        signatureHelpProvider = SignatureHelpProvider(compilationService, documentProvider, semanticResolver)
    }

    @Test
    fun `resolves String receiver type for length method`() = runTest {
        val uri = URI.create("file:///StringReceiverTest.groovy")
        val source = """
            class StringTest {
                def run() {
                    String text = "hello"
                    text.length()
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "text.length(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        // Should find String.length() method from classpath
        assertTrue(result.signatures.isNotEmpty(), "Should find signatures for String.length()")
        val labels = result.signatures.map { it.label }
        assertTrue(
            labels.any { it.contains("length()") },
            "Should find length() method. Found: $labels",
        )
    }

    @Test
    fun `resolves ArrayList receiver type for add method`() = runTest {
        val uri = URI.create("file:///ArrayListReceiverTest.groovy")
        val source = """
            import java.util.ArrayList
            
            class ListTest {
                def run() {
                    ArrayList list = new ArrayList()
                    list.add("item")
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "list.add(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        assertTrue(result.signatures.isNotEmpty(), "Should find signatures for ArrayList.add()")
        val labels = result.signatures.map { it.label }
        assertTrue(
            labels.any { it.contains("add(") },
            "Should find add() method. Found: $labels",
        )
    }

    @Test
    fun `infers type from variable initializer for def variables`() = runTest {
        val uri = URI.create("file:///DefVariableInference.groovy")
        val source = """
            class DefTest {
                def run() {
                    def items = new ArrayList()
                    items.add("test")
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "items.add(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        // SemanticTypeResolver should infer ArrayList from the initializer
        assertTrue(result.signatures.isNotEmpty(), "Should infer ArrayList type and find add() signatures")
        val labels = result.signatures.map { it.label }
        assertTrue(
            labels.any { it.contains("add(") },
            "Should find ArrayList.add() through type inference. Found: $labels",
        )
    }

    @Test
    fun `resolves receiver type for chained expressions`() = runTest {
        val uri = URI.create("file:///ChainedExpression.groovy")
        val source = """
            class Chain {
                def run() {
                    String result = "hello".toUpperCase()
                    result.length()
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "result.length(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        assertTrue(result.signatures.isNotEmpty(), "Should resolve String type for chained method call")
    }

    @Test
    fun `handles primitive wrapper types correctly`() = runTest {
        val uri = URI.create("file:///PrimitiveWrapper.groovy")
        val source = """
            class PrimitiveTest {
                def run() {
                    Integer num = 42
                    num.toString()
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "num.toString(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        assertTrue(result.signatures.isNotEmpty(), "Should find Integer.toString() signatures")
        val labels = result.signatures.map { it.label }
        assertTrue(
            labels.any { it.contains("toString()") },
            "Should find toString() method. Found: $labels",
        )
    }

    @Test
    fun `falls back to Object for unresolvable types`() = runTest {
        val uri = URI.create("file:///FallbackToObject.groovy")
        val source = """
            class FallbackTest {
                def run() {
                    def unknown
                    unknown.toString()
                }
            }
        """.trimIndent()

        compile(uri, source)
        val position = positionAfter(source, "unknown.toString(")
        val result = signatureHelpProvider.provideSignatureHelp(uri.toString(), position)

        // Even for unknown types, should find Object methods
        assertTrue(result.signatures.isNotEmpty(), "Should find Object.toString() as fallback")
    }

    // Helper methods
    private suspend fun compile(uri: URI, source: String) {
        documentProvider.put(uri, source)
        compilationService.compile(uri, source)
    }

    private fun positionAfter(source: String, snippet: String): Position {
        val lines = source.lines()
        lines.forEachIndexed { index, line ->
            if (line.contains(snippet)) {
                val column = line.indexOf(snippet) + snippet.length
                return Position(index, column)
            }
        }
        error("Snippet '$snippet' not found in source")
    }
}
