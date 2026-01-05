package com.github.albertocavalcante.groovyparser.ast.expr

import com.github.albertocavalcante.groovyparser.GroovyParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClosureExprTest {

    private val parser = GroovyParser()

    @Test
    fun `parse closure expression`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {
                    def closure = { x -> x * 2 }
                }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        // Closure parsing validated through successful parse
    }
}
