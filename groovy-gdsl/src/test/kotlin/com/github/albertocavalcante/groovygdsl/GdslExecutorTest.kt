package com.github.albertocavalcante.groovygdsl

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class GdslExecutorTest {

    private val executor = GdslExecutor()

    @Test
    fun `should execute simple GDSL script`() {
        val script = """
            def ctx = context(ctype: "java.lang.String")
            contributor(ctx) {
                method(name: "foo", type: "void", params: [:])
            }
        """.trimIndent()

        assertDoesNotThrow {
            executor.execute(script, "test.gdsl")
        }
    }

    @Test
    fun `should execute Spock GDSL sample`() {
        val script = this::class.java.getResource("/bundled-gdsls/spock.gdsl")?.readText()
            ?: throw IllegalStateException("Spock GDSL sample not found")

        assertDoesNotThrow {
            executor.execute(script, "spock.gdsl")
        }
    }

    @Test
    fun `should execute Grails GDSL sample`() {
        val script = this::class.java.getResource("/bundled-gdsls/grails.gdsl")?.readText()
            ?: throw IllegalStateException("Grails GDSL sample not found")

        assertDoesNotThrow {
            executor.execute(script, "grails.gdsl")
        }
    }
}
