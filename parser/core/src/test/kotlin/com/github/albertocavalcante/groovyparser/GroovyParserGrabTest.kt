package com.github.albertocavalcante.groovyparser

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

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
}
