package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `@Grab` is handled by a Groovy global transform that resolves dependencies through Ivy, which the
 * language server does not ship. Opening such a file must not throw out of the parser.
 */
class GroovyParserGrabTest {
    private val parser = GroovyParserFacade()

    @Test
    fun `parse does not throw for a script using @Grab`() {
        val code = """
            @Grab('org.apache.commons:commons-lang3:3.14.0')
            import org.apache.commons.lang3.StringUtils

            println StringUtils.capitalize("x")
        """.trimIndent()

        val result = parser.parse(ParseRequest(URI.create("file:///grab.groovy"), code))

        assertNotNull(result.ast, "AST of the buffer should survive an unresolvable @Grab")
    }

    @Test
    fun `parse does not throw for @Grab inside a class file`() {
        val code = """
            @Grab('org.apache.commons:commons-lang3:3.14.0')
            class Foo { int x }
        """.trimIndent()

        val result = parser.parse(ParseRequest(URI.create("file:///Foo.groovy"), code))

        assertTrue(result.ast != null)
    }

    @Test
    fun `a missing class thrown by a transform becomes a diagnostic instead of escaping`() {
        val result = parser.parse(ParseRequest(URI.create("file:///LinkageBomb.groovy"), "class LinkageBomb {}"))

        assertTrue(
            result.diagnostics.any { it.message.contains("NoClassDefFoundError") },
            "expected a diagnostic naming the missing class, got ${result.diagnostics.map { it.message }}",
        )
    }

    @Test
    fun `a failed compile with no collected errors yields a diagnostic instead of an NPE`() {
        val code = "class FailsWithoutErrors {}"

        val result = parser.parse(ParseRequest(URI.create("file:///FailsWithoutErrors.groovy"), code))

        assertTrue(
            result.diagnostics.any { it.message.startsWith("Compilation failed:") },
            "expected a 'Compilation failed' diagnostic, got ${result.diagnostics.map { it.message }}",
        )
    }
}
