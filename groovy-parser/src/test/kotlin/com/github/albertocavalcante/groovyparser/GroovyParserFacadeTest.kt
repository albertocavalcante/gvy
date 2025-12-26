package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `parse populates recursive ast model by default`() {
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
            ),
        )

        assertTrue(result.isSuccessful)
        assertTrue(result.astModel is RecursiveAstVisitor, "AST model should be RecursiveAstVisitor")
        val recursiveNodes = result.astModel.getAllNodes()
        assertTrue(recursiveNodes.isNotEmpty(), "AST model should collect nodes")
        val classCount = recursiveNodes.count { it is org.codehaus.groovy.ast.ClassNode }
        assertTrue(classCount >= 1, "Recursive visitor should track class nodes")
    }

    @Test
    fun `astModel provides nodes at common positions`() {
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

        val result = parser.parse(
            ParseRequest(
                uri = uri,
                content = code,
            ),
        )

        val positions = listOf(
            com.github.albertocavalcante.groovyparser.ast.types.Position(0, 6) to "class name",
            com.github.albertocavalcante.groovyparser.ast.types.Position(1, 6) to "method name",
            com.github.albertocavalcante.groovyparser.ast.types.Position(1, 17) to "parameter",
            com.github.albertocavalcante.groovyparser.ast.types.Position(2, 8) to "if condition variable",
            com.github.albertocavalcante.groovyparser.ast.types.Position(3, 14) to "println argument",
        )

        positions.forEach { (pos, label) ->
            val node = result.astModel.getNodeAt(uri, pos)
            assertNotNull(node, "AST model missing $label at $pos")
        }
    }
}
