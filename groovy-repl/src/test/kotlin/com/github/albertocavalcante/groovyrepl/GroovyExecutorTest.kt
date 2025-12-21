package com.github.albertocavalcante.groovyrepl

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * TDD tests for GroovyExecutor - the core code execution engine.
 *
 * The executor maintains state across cell executions (like a REPL),
 * captures stdout/stderr, and returns execution results.
 */
class GroovyExecutorTest {

    @Test
    fun `should execute simple expression and return result`() {
        // Given: A Groovy executor
        val executor = GroovyExecutor()

        // When: Executing an expression
        val result = executor.execute("1 + 2")

        // Then: Should return the result
        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isEqualTo(3)
    }

    @Test
    fun `should execute println and capture stdout`() {
        // Given: An executor
        val executor = GroovyExecutor()

        // When: Executing println
        val result = executor.execute("println 'Hello, REPL!'")

        // Then: Should capture output
        assertThat(result.isSuccess).isTrue()
        assertThat(result.stdout).contains("Hello, REPL!")
    }

    @Test
    fun `should persist variables across executions`() {
        // Given: An executor with prior state
        val executor = GroovyExecutor()
        executor.execute("x = 10")

        // When: Using the variable in a new execution
        val result = executor.execute("x * 2")

        // Then: Should remember variable
        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isEqualTo(20)
    }

    @Test
    fun `should handle script-local def variables`() {
        // Note: In Groovy scripts, 'def' creates a script-local variable
        // that doesn't persist across separate evaluate() calls.
        // To persist, omit 'def' or use '@Field'.

        // Given: An executor
        val executor = GroovyExecutor()

        // When: Using def and variable in same script
        val result = executor.execute(
            """
            def name = 'Groovy'
            name
            """.trimIndent(),
        )

        // Then: Should work within same script
        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isEqualTo("Groovy")
    }

    @Test
    fun `should capture syntax errors`() {
        // Given: An executor
        val executor = GroovyExecutor()

        // When: Executing invalid code
        val result = executor.execute("def x = (")

        // Then: Should report error
        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorMessage).isNotEmpty()
    }

    @Test
    fun `should capture runtime exceptions`() {
        // Given: An executor
        val executor = GroovyExecutor()

        // When: Executing code that throws
        val result = executor.execute("throw new RuntimeException('test error')")

        // Then: Should report error
        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorMessage).contains("test error")
    }

    @Test
    fun `should capture stderr output`() {
        // Given: An executor
        val executor = GroovyExecutor()

        // When: Writing to stderr
        val result = executor.execute("System.err.println('error output')")

        // Then: Should capture stderr
        assertThat(result.stderr).contains("error output")
    }

    @Test
    fun `should support closures and higher-order functions`() {
        // Given: An executor
        val executor = GroovyExecutor()

        // When: Using closures
        val result = executor.execute("[1, 2, 3].collect { it * 2 }")

        // Then: Should work
        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isEqualTo(listOf(2, 4, 6))
    }

    @Test
    fun `should define and use methods in same script`() {
        // Note: Methods defined with 'def' are script-local.
        // To persist methods across cells, use the MethodClosure pattern.

        // Given: An executor
        val executor = GroovyExecutor()

        // When: Defining and calling method in same script
        val groovyCode = "def greet(n) { 'Hello, ' + n + '!' }\ngreet('World')"
        val result = executor.execute(groovyCode)

        // Then: Should work
        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isEqualTo("Hello, World!")
    }

    @Test
    fun `should reset state when requested`() {
        // Given: An executor with state
        val executor = GroovyExecutor()
        executor.execute("x = 100")

        // When: Resetting and accessing the variable
        executor.reset()
        val result = executor.execute("x")

        // Then: Variable should not exist
        assertThat(result.isSuccess).isFalse()
    }

    @Test
    fun `should handle multiline code`() {
        // Given: An executor
        val executor = GroovyExecutor()

        // When: Executing multiline code
        val result = executor.execute(
            """
            def numbers = [1, 2, 3, 4, 5]
            def sum = numbers.sum()
            def avg = sum / numbers.size()
            avg
            """.trimIndent(),
        )

        // Then: Should work (returns BigDecimal due to Groovy division)
        assertThat(result.isSuccess).isTrue()
        // Groovy division returns BigDecimal, so we compare numerically
        assertThat((result.value as Number).toInt()).isEqualTo(3)
    }

    @Test
    fun `should expose current bindings`() {
        // Given: An executor with variables
        val executor = GroovyExecutor()
        executor.execute("name = 'Groovy'")
        executor.execute("version = 4")

        // When: Getting bindings
        val bindings = executor.getBindings()

        // Then: Should contain the variables
        assertThat(bindings).containsEntry("name", "Groovy")
        assertThat(bindings).containsEntry("version", 4)
    }

    @Test
    fun `should have Apache Ivy available for Grape support`() {
        // This test ensures the Ivy dependency is on the classpath.
        // Without Ivy, @Grab annotations fail with:
        // NoClassDefFoundError: org/apache/ivy/util/MessageLogger

        // Given: An executor
        val executor = GroovyExecutor()

        // When: Loading Ivy's MessageLogger class (the class from the error)
        val result = executor.execute("Class.forName('org.apache.ivy.util.MessageLogger')")

        // Then: Ivy should be available
        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isInstanceOf(Class::class.java)
    }

    @Test
    fun `should have Grape engine available`() {
        // Verifies that Groovy's Grape (dependency grabber) can be instantiated.
        // This is a prerequisite for @Grab to work.

        // Given: An executor
        val executor = GroovyExecutor()

        // When: Getting the Grape instance
        val result = executor.execute("groovy.grape.Grape.getInstance()")

        // Then: Should succeed without NoClassDefFoundError
        assertThat(result.isSuccess).isTrue()
        assertThat(result.value).isNotNull()
    }
}
