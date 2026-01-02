package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisitorCoverageTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `visit break and continue`() {
        val code = """
            for (int i = 0; i < 10; i++) {
                if (i == 5) break
                if (i < 2) continue
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `visit throw statement`() {
        val code = """
            def boom() {
                throw new RuntimeException("boom")
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `visit annotated members`() {
        val code = """
            @Deprecated
            class Old {
                @Override
                String toString() { return "old" }
                
                @Deprecated
                String field = "old"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
    }

    @Test
    fun `visit if else`() {
        val code = """
            if (true) {
                println "true"
            } else {
                println "false"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)
    }
}
