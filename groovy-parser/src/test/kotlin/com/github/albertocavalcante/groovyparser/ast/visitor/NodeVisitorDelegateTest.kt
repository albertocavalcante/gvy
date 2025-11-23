package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeVisitorDelegateTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `visit while loop`() {
        val code = """
            while (true) {
                println "looping"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
        // The parser uses the visitor internally to build the symbol table or check positions.
        // Just parsing successfully implies the visitor didn't crash on this construct.
    }

    @Test
    fun `visit do while loop`() {
        val code = """
            do {
                println "once"
            } while (false)
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `visit try catch finally`() {
        val code = """
            try {
                throw new Exception()
            } catch (Exception e) {
                println "caught"
            } finally {
                println "done"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `visit switch statement`() {
        val code = """
            def x = 1
            switch (x) {
                case 1:
                    println "one"
                    break
                default:
                    println "other"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
    }
}
