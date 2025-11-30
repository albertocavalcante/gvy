package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GroovyParserFacadeTest {

    private val parser = GroovyParserFacade()
    private val tempDir = kotlin.io.path.createTempDirectory("parser-test")

    @Test
    fun `parse valid groovy snippet`() {
        val code = """
            class Greeting {
                String message() { "hi" }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Greeting.groovy"),
                content = code,
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.ast)
        assertEquals(0, result.diagnostics.size)
    }

    @Test
    fun `parse invalid groovy snippet emits diagnostics`() {
        val code = """
            class Broken {
                void brokenMethod( {
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Broken.groovy"),
                content = code,
            ),
        )

        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.any { it.severity == ParserSeverity.ERROR })
    }

    @Test
    fun `workspace sources are added to compilation unit`() {
        val extraSource = tempDir.resolve("Extra.groovy").toFile()
        extraSource.writeText(
            """
            class ExtraSource {
                String value = "ok"
            }
            """.trimIndent(),
        )

        val code = """
            class UsesExtra {
                ExtraSource ref = new ExtraSource()
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///UsesExtra.groovy"),
                content = code,
                workspaceSources = listOf(extraSource.toPath()),
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.ast)
        assertEquals(0, result.diagnostics.size)
    }

    @Test
    fun `parse with recursive visitor flag populates recursive visitor`() {
        val code = """
            class Sample {
                def method(int x) {
                    if (x > 0) {
                        println x
                    }
                }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Sample.groovy"),
                content = code,
                useRecursiveVisitor = true,
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.recursiveVisitor, "Recursive visitor should be created when flag is enabled")
        val recursiveNodes = result.recursiveVisitor!!.getAllNodes()
        assertTrue(recursiveNodes.isNotEmpty(), "Recursive visitor should collect nodes")
        val classCount = recursiveNodes.count { it is org.codehaus.groovy.ast.ClassNode }
        assertTrue(classCount >= 1, "Recursive visitor should track class nodes")
    }

    @Test
    fun `astModel falls back to legacy visitor when recursive visitor is disabled`() {
        val code = "class Legacy { String name }"

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Legacy.groovy"),
                content = code,
                useRecursiveVisitor = false,
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.astVisitor)
        assertSame(result.astVisitor, result.astModel, "astModel should use legacy visitor when recursive is disabled")
    }

    @Test
    fun `astModel prefers recursive visitor when enabled`() {
        val code = "class Preferred { void run() {} }"

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Preferred.groovy"),
                content = code,
                useRecursiveVisitor = true,
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.recursiveVisitor)
        assertSame(
            result.recursiveVisitor,
            result.astModel,
            "astModel should point to recursive visitor when enabled",
        )
    }

    @Test
    fun `recursive visitor matches legacy node lookup for common constructs`() {
        val code = """
            class Sample {
              def method(int x) {
                if (x > 0) {
                  println x
                }
              }
            }
        """.trimIndent()
        val uri = URI.create("file:///Sample.groovy")

        val legacy = parser.parse(
            ParseRequest(
                uri = uri,
                content = code,
                useRecursiveVisitor = false,
            ),
        ).astVisitor
        val recursive = parser.parse(
            ParseRequest(
                uri = uri,
                content = code,
                useRecursiveVisitor = true,
            ),
        ).recursiveVisitor

        requireNotNull(legacy) { "Legacy visitor missing" }
        requireNotNull(recursive) { "Recursive visitor missing" }

        val positions = listOf(
            com.github.albertocavalcante.groovyparser.ast.types.Position(0, 6) to "class name",
            com.github.albertocavalcante.groovyparser.ast.types.Position(1, 6) to "method name",
            com.github.albertocavalcante.groovyparser.ast.types.Position(1, 17) to "parameter",
            com.github.albertocavalcante.groovyparser.ast.types.Position(2, 8) to "if condition variable",
            com.github.albertocavalcante.groovyparser.ast.types.Position(3, 14) to "println argument",
        )

        positions.forEach { (pos, label) ->
            val legacyNode = legacy.getNodeAt(uri, pos)
            val recursiveNode = recursive.getNodeAt(uri, pos)
            assertNotNull(legacyNode, "Legacy visitor missing $label at $pos")
            assertNotNull(recursiveNode, "Recursive visitor missing $label at $pos")
            assertEquals(
                legacyNode.javaClass,
                recursiveNode.javaClass,
                "Node type mismatch for $label at $pos",
            )
        }
    }
}
