package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * `@ASTTest` runs its closure inside the compiler JVM when `groovy-test` is on the compiled project's
 * classpath (typical for Spock projects). Analysis must never execute source text from the workspace.
 */
class AstTestExecutionTest {
    @TempDir
    lateinit var tempDir: Path

    private fun jarOf(className: String): Path =
        Path.of(Class.forName(className).protectionDomain.codeSource.location.toURI())

    private fun parse(code: String, phase: Int) = GroovyParserFacade().parse(
        ParseRequest(
            uri = URI.create("file:///A.groovy"),
            content = code,
            classpath = listOf(jarOf("groovy.lang.Closure"), jarOf("groovy.transform.ASTTest")),
            compilePhase = phase,
        ),
    )

    private fun probe(marker: Path, annotation: String, imports: String = "") = """
        $imports
        $annotation(phase=org.codehaus.groovy.control.CompilePhase.SEMANTIC_ANALYSIS,
            value={ new File('${marker.toString().replace("\\", "/")}').text = 'x' })
        class A {}
    """.trimIndent()

    @Test
    fun `ASTTest closure does not run at any analysis phase`() {
        for (phase in listOf(Phases.CONVERSION, Phases.SEMANTIC_ANALYSIS, Phases.CANONICALIZATION)) {
            val marker = tempDir.resolve("pwned-$phase")
            val result = parse(probe(marker, "@groovy.transform.ASTTest"), phase)

            assertFalse(marker.exists(), "@ASTTest closure executed at phase $phase")
            assertNotNull(result.ast, "the AST must survive the stripped annotation (phase $phase)")
        }
    }

    @Test
    fun `ASTTest does not run through a simple name or an alias`() {
        val simple = tempDir.resolve("simple")
        parse(probe(simple, "@ASTTest", "import groovy.transform.ASTTest"), Phases.SEMANTIC_ANALYSIS)
        val star = tempDir.resolve("star")
        parse(probe(star, "@ASTTest", "import groovy.transform.*"), Phases.SEMANTIC_ANALYSIS)
        val aliased = tempDir.resolve("aliased")
        parse(probe(aliased, "@T", "import groovy.transform.ASTTest as T"), Phases.SEMANTIC_ANALYSIS)

        assertFalse(simple.exists(), "simple-name @ASTTest executed")
        assertFalse(star.exists(), "star-imported @ASTTest executed")
        assertFalse(aliased.exists(), "aliased @ASTTest executed")
    }

    @Test
    fun `ASTTest on members and locals does not run`() {
        val marker = tempDir.resolve("members")
        val path = marker.toString().replace("\\", "/")
        val closure = "{ new File('$path').text = 'x' }"
        val code = """
            class A {
                @groovy.transform.ASTTest(value=$closure) int field
                @groovy.transform.ASTTest(value=$closure) void m(@groovy.transform.ASTTest(value=$closure) int p) {
                    @groovy.transform.ASTTest(value=$closure) def local = 1
                }
            }
        """.trimIndent()

        parse(code, Phases.SEMANTIC_ANALYSIS)

        assertFalse(marker.exists(), "@ASTTest on a member or local executed")
    }
}
