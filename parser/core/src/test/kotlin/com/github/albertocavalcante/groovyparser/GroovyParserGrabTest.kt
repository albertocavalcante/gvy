package com.github.albertocavalcante.groovyparser

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `@Grab` needs Ivy, which the language server does not ship; parsing such a file must still work. */
class GroovyParserGrabTest {
    @Test
    fun `parse does not throw for a script using @Grab`() {
        val code = """
            @Grab('org.apache.commons:commons-lang3:3.14.0')
            import org.apache.commons.lang3.StringUtils

            println StringUtils.capitalize("x")
        """.trimIndent()

        val result = GroovyParser(ParserConfiguration()).parse(code)

        assertNotNull(result.result.orElse(null), "AST of the buffer should survive an unresolvable @Grab")
    }

    @Test
    fun `a missing class thrown by a transform is reported as a problem with its cause`() {
        val result = GroovyParser(ParserConfiguration()).parse("class LinkageBomb {}")

        val message = result.problems.map { it.message }.firstOrNull { it.contains("NoClassDefFoundError") }
        assertNotNull(message, "expected a problem naming the missing class, got ${result.problems}")
        assertTrue(message.contains("org.apache.ivy.Ivy"), "the cause should be included: $message")
    }
}
