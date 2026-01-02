package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroovyParserCompilePhaseTest {

    private val parser = GroovyParserFacade()

    @Test
    fun `parse supports compiling only to conversion phase`() {
        val code =
            """
            class Greeting {
                String message() { "hi" }
            }
            """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Greeting.groovy"),
                content = code,
                compilePhase = Phases.CONVERSION,
            ),
        )

        assertTrue(result.isSuccessful)
        assertNotNull(result.ast)
    }

    @Test
    fun `parse defaults compilePhase to canonicalization`() {
        val code =
            """
            class Greeting {
                String message() { "hi" }
            }
            """.trimIndent()

        val request =
            ParseRequest(
                uri = URI.create("file:///Greeting.groovy"),
                content = code,
            )

        assertEquals(Phases.CANONICALIZATION, request.compilePhase)

        val result = parser.parse(request)
        assertTrue(result.isSuccessful)
        assertNotNull(result.ast)
    }

    @Test
    fun `conversion phase includes statement labels for spock style blocks`() {
        val code =
            """
            class FooSpec {
                def feature() {
                    given:
                    def x = 1
                    when:
                    x++
                    then:
                    x == 2
                }
            }
            """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///FooSpec.groovy"),
                content = code,
                compilePhase = Phases.CONVERSION,
            ),
        )

        assertTrue(result.isSuccessful)
        val ast = result.ast
        assertNotNull(ast)

        val clazz = ast.classes.single { it.nameWithoutPackage == "FooSpec" }
        val method = clazz.methods.single { it.name == "feature" }
        val labels = collectStatementLabels(method.code)

        assertTrue("given" in labels, "Expected to find 'given' label; got: $labels")
        assertTrue("when" in labels, "Expected to find 'when' label; got: $labels")
        assertTrue("then" in labels, "Expected to find 'then' label; got: $labels")
    }

    @Test
    fun `conversion phase still emits syntax error diagnostics`() {
        val code =
            """
            class Broken {
                void brokenMethod( {
            }
            """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Broken.groovy"),
                content = code,
                compilePhase = Phases.CONVERSION,
            ),
        )

        assertFalse(result.isSuccessful)
        assertTrue(result.diagnostics.any { it.severity == ParserSeverity.ERROR })
    }

    private fun collectStatementLabels(statement: Statement?): List<String> {
        if (statement == null) return emptyList()

        val labels = mutableListOf<String>()

        fun visit(stmt: Statement) {
            // NOTE: We prefer the list-based API to avoid using deprecated `getStatementLabel()`.
            // TODO: When we implement the production Spock block indexer, rely on statement labels at method-body
            // top-level (Spock's own parser looks there) and avoid generic deep recursion.
            labels.addAll(stmt.statementLabels.orEmpty())
            when (stmt) {
                is BlockStatement -> stmt.statements.forEach(::visit)
            }
        }

        visit(statement)
        return labels
    }
}
