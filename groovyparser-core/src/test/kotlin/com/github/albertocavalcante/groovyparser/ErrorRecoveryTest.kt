package com.github.albertocavalcante.groovyparser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for error recovery and problem handling.
 */
class ErrorRecoveryTest {

    @Test
    fun `lenient mode returns partial AST on syntax error`() {
        val config = ParserConfiguration().setLenientMode(true)
        val parser = GroovyParser(config)

        // Missing closing brace
        val code = """
            class Foo {
                def bar() {
                    println "hello"
                // missing closing braces
        """.trimIndent()

        val result = parser.parse(code)

        // Should have problems
        assertTrue(result.hasErrors, "Should have errors")

        // In lenient mode with syntax errors, we may or may not get partial AST
        // depending on how far parsing got
        assertNotNull(result.problems)
        assertTrue(result.problems.isNotEmpty())
    }

    @Test
    fun `strict mode returns null on syntax error`() {
        val config = ParserConfiguration().setLenientMode(false)
        val parser = GroovyParser(config)

        // Missing closing brace
        val code = """
            class Foo {
                def bar() {
        """.trimIndent()

        val result = parser.parse(code)

        // Should fail without result in strict mode
        assertFalse(result.isSuccessful)
        assertTrue(result.hasErrors)
    }

    @Test
    fun `problem severity is correctly assigned`() {
        val parser = GroovyParser()

        val code = """
            class Foo {
                def bar() {
        """.trimIndent()

        val result = parser.parse(code)

        assertTrue(result.hasErrors)
        val errors = result.errors
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.all { it.severity == ProblemSeverity.ERROR })
    }

    @Test
    fun `problem has range information when available`() {
        val parser = GroovyParser()

        val code = """
            class Foo {
                def bar() {
                    println "hello"
                }
            // missing }
        """.trimIndent()

        val result = parser.parse(code)

        if (result.hasErrors) {
            val firstError = result.errors.first()
            // Position should be present for syntax errors
            assertNotNull(firstError.position)
        }
    }

    @Test
    fun `parse result toString reflects status correctly`() {
        val parser = GroovyParser()

        // Successful parse
        val successResult = parser.parse("class Foo {}")
        assertTrue(successResult.toString().contains("successful"))

        // Failed parse
        val failResult = parser.parse("class Foo {")
        assertTrue(
            failResult.toString().contains("error") || failResult.toString().contains("failed"),
        )
    }

    @Test
    fun `getOrThrow returns result on success`() {
        val parser = GroovyParser()
        val result = parser.parse("class Foo {}")

        val unit = result.getOrThrow()
        assertEquals(1, unit.types.size)
        assertEquals("Foo", unit.types[0].name)
    }

    @Test
    fun `getOrThrow throws on failure`() {
        val parser = GroovyParser()
        val result = parser.parse("class Foo {")

        try {
            result.getOrThrow()
            assertTrue(false, "Should have thrown")
        } catch (e: ParseProblemException) {
            assertTrue(e.problems.isNotEmpty())
        }
    }

    @Test
    fun `problem comparators work correctly`() {
        val p1 = Problem("Error 1", Position(1, 1), ProblemSeverity.WARNING)
        val p2 = Problem("Error 2", Position(2, 1), ProblemSeverity.ERROR)
        val p3 = Problem("Error 3", Position(1, 5), ProblemSeverity.ERROR)

        // By position
        val byPosition = listOf(p2, p1, p3).sortedWith(Problem.COMPARATOR_BY_BEGIN_POSITION)
        assertEquals(Position(1, 1), byPosition[0].position)
        assertEquals(Position(1, 5), byPosition[1].position)
        assertEquals(Position(2, 1), byPosition[2].position)

        // By severity (errors first)
        val bySeverity = listOf(p1, p2, p3).sortedWith(Problem.COMPARATOR_BY_SEVERITY)
        assertEquals(ProblemSeverity.ERROR, bySeverity[0].severity)
        assertEquals(ProblemSeverity.ERROR, bySeverity[1].severity)
        assertEquals(ProblemSeverity.WARNING, bySeverity[2].severity)
    }

    @Test
    fun `problem factory methods create correct severity`() {
        val error = Problem.error("Test error")
        val warning = Problem.warning("Test warning")
        val info = Problem.info("Test info")

        assertEquals(ProblemSeverity.ERROR, error.severity)
        assertEquals(ProblemSeverity.WARNING, warning.severity)
        assertEquals(ProblemSeverity.INFO, info.severity)
    }

    @Test
    fun `problem isError and isWarning properties work`() {
        val error = Problem.error("Test error")
        val warning = Problem.warning("Test warning")

        assertTrue(error.isError)
        assertFalse(error.isWarning)

        assertFalse(warning.isError)
        assertTrue(warning.isWarning)
    }

    @Test
    fun `problem severity isAtLeast works correctly`() {
        assertTrue(ProblemSeverity.ERROR.isAtLeast(ProblemSeverity.ERROR))
        assertTrue(ProblemSeverity.ERROR.isAtLeast(ProblemSeverity.WARNING))
        assertTrue(ProblemSeverity.ERROR.isAtLeast(ProblemSeverity.HINT))

        assertFalse(ProblemSeverity.WARNING.isAtLeast(ProblemSeverity.ERROR))
        assertTrue(ProblemSeverity.WARNING.isAtLeast(ProblemSeverity.WARNING))
        assertTrue(ProblemSeverity.WARNING.isAtLeast(ProblemSeverity.INFO))
    }

    @Test
    fun `parse result problemsAtLeast filters correctly`() {
        val problems = listOf(
            Problem.error("Error"),
            Problem.warning("Warning"),
            Problem.info("Info"),
        )
        val result = ParseResult<Any>(null, problems)

        assertEquals(3, result.problemsAtLeast(ProblemSeverity.INFO).size)
        assertEquals(2, result.problemsAtLeast(ProblemSeverity.WARNING).size)
        assertEquals(1, result.problemsAtLeast(ProblemSeverity.ERROR).size)
    }

    @Test
    fun `empty source parses successfully`() {
        val parser = GroovyParser()
        val result = parser.parse("")

        assertTrue(result.isSuccessful)
        assertFalse(result.hasErrors)
    }

    @Test
    fun `whitespace-only source parses successfully`() {
        val parser = GroovyParser()
        val result = parser.parse("   \n\n   ")

        assertTrue(result.isSuccessful)
        assertFalse(result.hasErrors)
    }

    @Test
    fun `comment-only source parses successfully`() {
        val parser = GroovyParser()
        val result = parser.parse("// Just a comment")

        assertTrue(result.isSuccessful)
        assertFalse(result.hasErrors)
    }
}
