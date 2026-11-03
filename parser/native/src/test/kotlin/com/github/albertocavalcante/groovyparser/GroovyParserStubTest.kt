package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserSeverity
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A class Groovy cannot resolve stops it resolving anything else. The parser compiles an empty stub for
 * each missing class so Groovy itself resolves the rest; these tests read the resolved types off the AST.
 */
class GroovyParserStubTest {

    private val parser = GroovyParserFacade()

    @TempDir
    lateinit var tempDir: Path

    private fun workspaceFile(name: String, text: String): Path =
        tempDir.resolve(name).also { it.toFile().writeText(text) }

    private fun parse(content: String, vararg workspace: Path, phase: Int = Phases.CANONICALIZATION): ParseResult =
        parser.parse(
            ParseRequest(
                uri = URI.create("file:///w/Requested.groovy"),
                content = content,
                workspaceSources = workspace.toList(),
                compilePhase = phase,
            ),
        )

    private fun ParseResult.fieldType(className: String, field: String): String? =
        ast?.classes?.firstOrNull { it.name == className }?.getField(field)?.type?.name

    private fun ParseResult.errors() = diagnostics.filter { it.severity == ParserSeverity.ERROR }

    @Test
    fun `a missing superclass is stubbed so the other types of the file resolve`() {
        val hello = workspaceFile("HelloService.groovy", "package com.example\nclass HelloService {}")

        val result = parse(
            "package com.example\nimport spock.lang.Specification\nclass S extends Specification { HelloService s }",
            hello,
        )

        assertEquals("com.example.HelloService", result.fieldType("com.example.S", "s"))
        assertTrue(
            result.errors().any { it.message.contains("unable to resolve class spock.lang.Specification") },
            "the original error must still be shown: ${result.diagnostics}",
        )
        assertEquals(listOf("spock.lang.Specification"), result.outcome.stubbedClasses)
        assertTrue(result.outcome.isDegraded)
        assertNull(result.outcome.fallback)
        assertEquals(Phases.CANONICALIZATION, result.outcome.achievedPhase)
    }

    @Test
    fun `a workspace file extending a missing class is not excluded and its class stays usable`() {
        val broken = workspaceFile(
            "Spec.groovy",
            "package com.example\nimport spock.lang.Specification\nclass Spec extends Specification {}",
        )

        val result = parse("package com.example\nclass Uses { Spec spec }", broken)

        assertEquals("com.example.Spec", result.fieldType("com.example.Uses", "spec"))
        assertTrue(result.outcome.excludedSources.isEmpty(), "got: ${result.outcome.excludedSources}")
        assertEquals(listOf("spock.lang.Specification"), result.outcome.stubbedClasses)
        assertTrue(
            result.errors().none { it.message.contains("unable to resolve class spock.lang.Specification") },
            "the error belongs to the other file: ${result.diagnostics}",
        )
    }

    @Test
    fun `a missing interface is restubbed as an interface and the compile reaches the requested phase`() {
        val result = parse("class Impl implements com.missing.Iface {}", phase = Phases.CLASS_GENERATION)

        assertEquals(Phases.CLASS_GENERATION, result.outcome.achievedPhase)
        assertEquals(listOf("com.missing.Iface"), result.outcome.stubbedClasses)
        assertNull(result.outcome.fallback)
        assertTrue(result.errors().all { it.message.contains("unable to resolve class") }, "${result.diagnostics}")
    }

    @Test
    fun `a missing generic supertype is stubbed with the type parameters it was used with`() {
        val result = parse("class G extends com.missing.Base<String> { String s }")

        assertEquals(Phases.CANONICALIZATION, result.outcome.achievedPhase)
        assertEquals("java.lang.String", result.fieldType("G", "s"), "${result.diagnostics} ${result.outcome}")
        assertEquals(listOf("com.missing.Base"), result.outcome.stubbedClasses)
        assertTrue(result.errors().none { it.message.contains("takes no parameters") }, "${result.diagnostics}")
    }

    @Test
    fun `a missing annotation is stubbed as an annotation`() {
        val result = parse("@com.missing.Marker class A { String s }", phase = Phases.CLASS_GENERATION)

        assertEquals(Phases.CLASS_GENERATION, result.outcome.achievedPhase)
        assertEquals(listOf("com.missing.Marker"), result.outcome.stubbedClasses)
        assertTrue(result.errors().none { it.message.contains("is not an annotation") }, "${result.diagnostics}")
    }

    @Test
    fun `a missing nested looking class is not stubbed`() {
        val result = parse("class A extends Outer.Inner {}")

        assertTrue(result.outcome.stubbedClasses.isEmpty(), "got: ${result.outcome.stubbedClasses}")
        assertTrue(result.errors().any { it.message.contains("unable to resolve class Outer.Inner") })
    }

    @Test
    fun `a nested class wins over a top level class of the same name once the superclass is stubbed`() {
        val outer = workspaceFile(
            "Outer.groovy",
            """
                package com.example
                import com.missing.Missing
                class Outer extends Missing { static class Inner {}
                    Inner field }
            """.trimIndent(),
        )
        val inner = workspaceFile("Inner.groovy", "package com.example\nclass Inner {}")

        val result = parse("package com.example\nclass Uses { Outer o }", outer, inner)
        assertEquals(listOf("com.missing.Missing"), result.outcome.stubbedClasses)

        val module = result.compilationUnit.ast.modules.single { it.classes.any { c -> c.name == "com.example.Outer" } }
        val field = module.classes.first { it.name == "com.example.Outer" }.getField("field")
        assertEquals("com.example.Outer\$Inner", field.type.name)
    }

    @Test
    fun `another file's bare missing name is not stubbed over a class this file imports`() {
        // B forgot its import; a stub p.TimeUnit would beat A's star import, as the package comes first.
        val other = workspaceFile("B.groovy", "package p\nclass B { TimeUnit u }")

        val result = parse("package p\nimport java.util.concurrent.*\nclass A { TimeUnit t }", other)

        assertEquals("java.util.concurrent.TimeUnit", result.fieldType("p.A", "t"))
        assertEquals(emptyList(), result.outcome.stubbedClasses)
    }

    @Test
    fun `a bare missing name in this file is stubbed in its package`() {
        val result = parse("package p\nclass A extends Missing { String s }")

        assertEquals(listOf("p.Missing"), result.outcome.stubbedClasses)
        assertEquals("java.lang.String", result.fieldType("p.A", "s"))
    }

    @Test
    fun `a stub never leaks into the ast or the diagnostics`() {
        val result = parse("class S extends spock.lang.Specification {}")

        assertNotNull(result.ast)
        assertTrue(result.ast!!.classes.none { it.name == "spock.lang.Specification" })
        assertTrue(!isStubSourceName(result.ast!!.context?.name), "ast is ${result.ast!!.context?.name}")
        assertTrue(result.diagnostics.none { it.message.contains("gvy-stub") }, "${result.diagnostics}")
    }
}
