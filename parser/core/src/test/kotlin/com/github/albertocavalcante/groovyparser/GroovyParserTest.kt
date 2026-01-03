package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GroovyParserTest {

    @Test
    fun `parse empty string returns successful result with empty compilation unit`() {
        val parser = GroovyParser()
        val result = parser.parse("")

        assertThat(result.isSuccessful).isTrue()
        assertThat(result.result).isPresent
    }

    @Test
    fun `parse simple class returns compilation unit with class`() {
        val parser = GroovyParser()
        val result = parser.parse("class Foo {}")

        assertThat(result.isSuccessful).isTrue()
        val unit = result.result.get()
        assertThat(unit.types).hasSize(1)
        assertThat(unit.types[0].name).isEqualTo("Foo")
    }

    @Test
    fun `parse invalid syntax returns result with problems`() {
        val parser = GroovyParser()
        val result = parser.parse("class { invalid }")

        assertThat(result.isSuccessful).isFalse()
        assertThat(result.problems).isNotEmpty()
    }

    @Test
    fun `parser with configuration uses configured settings`() {
        val config = ParserConfiguration()
            .setLanguageLevel(GroovyLanguageLevel.GROOVY_4_0)
            .setTabSize(4)

        val parser = GroovyParser(config)

        assertThat(parser.configuration.languageLevel).isEqualTo(GroovyLanguageLevel.GROOVY_4_0)
        assertThat(parser.configuration.tabSize).isEqualTo(4)
    }

    @Test
    fun `parse with package declaration`() {
        val parser = GroovyParser()
        val result = parser.parse(
            """
            package com.example
            
            class Foo {}
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val unit = result.result.get()
        assertThat(unit.packageDeclaration).isPresent
        assertThat(unit.packageDeclaration.get().name).isEqualTo("com.example")
    }

    @Test
    fun `parse with imports`() {
        val parser = GroovyParser()
        val result = parser.parse(
            """
            import java.util.List
            import java.util.Map
            
            class Foo {}
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val unit = result.result.get()
        assertThat(unit.imports).hasSize(2)
    }

    @Test
    fun `parse script without class`() {
        val parser = GroovyParser()
        val result = parser.parse(
            """
            println "Hello, World!"
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
    }

    @Test
    fun `parser can be reused for multiple parses`() {
        val parser = GroovyParser()

        val result1 = parser.parse("class A {}")
        val result2 = parser.parse("class B {}")

        assertThat(result1.isSuccessful).isTrue()
        assertThat(result2.isSuccessful).isTrue()
        assertThat(result1.result.get().types[0].name).isEqualTo("A")
        assertThat(result2.result.get().types[0].name).isEqualTo("B")
    }

    @Test
    fun `lenient mode returns empty CompilationUnit on compilation error with no moduleNode`() {
        // Issue 1: When lenient mode is enabled and there's a compilation error
        // but no recoverable moduleNode, should return empty CompilationUnit, NOT null
        val config = ParserConfiguration().setLenientMode(true)
        val parser = GroovyParser(config)

        // Code that causes severe compilation error with no recoverable AST
        // This should trigger CompilationFailedException and have no moduleNode
        val result = parser.parse("class {{{")

        // In lenient mode, should still return a result (not null)
        assertThat(result.result).isPresent
        assertThat(result.result.get()).isInstanceOf(CompilationUnit::class.java)
        // Should have problems recorded
        assertThat(result.problems).isNotEmpty()
        assertThat(result.isSuccessful).isFalse()
    }

    @Test
    fun `GroovyClassLoader is properly closed to prevent resource leak`() {
        // Issue 2: Verify that repeated parsing doesn't leak resources
        // by ensuring GroovyClassLoader is properly closed after each parse
        val parser = GroovyParser()

        // Parse multiple times - if classloader isn't closed, this could
        // eventually exhaust file handles or cause memory pressure
        repeat(100) { i ->
            val result = parser.parse("class Test$i { def method() { return $i } }")
            assertThat(result.isSuccessful).isTrue()
        }

        // If we got here without exceptions or hanging, the resource management is working
        // (A resource leak would eventually cause failures or extreme slowdown)
    }

    // TODO(#587): Extract helpers to reduce method length without losing coverage.
    //   See: https://github.com/albertocavalcante/gvy/issues/587
    @Test
    fun `parse is thread-safe with concurrent access`() {
        // Issue 3: Verify that parse() doesn't have race conditions
        // when called concurrently from multiple threads
        val threadCount = 20
        val iterations = 50
        val totalOperations = threadCount * iterations

        // Use a shared parser instance to test thread-safety
        val config = ParserConfiguration().setAttributeComments(true)
        val parser = GroovyParser(config)

        val executor = Executors.newFixedThreadPool(threadCount)

        // Create callable tasks for concurrent execution
        val tasks = (0 until threadCount).flatMap { threadId ->
            (0 until iterations).map { iteration ->
                Callable {
                    // Each task parses different code with unique comments
                    val className = "TestClass${threadId}_$iteration"
                    val methodName = "method${threadId}_$iteration"
                    val classComment = "Comment for $className"
                    val methodComment = "Method $methodName"
                    val code = """
                        /** $classComment */
                        class $className {
                            /** $methodComment */
                            def $methodName() {
                                return ${threadId * 1000 + iteration}
                            }
                        }
                    """.trimIndent()

                    // Parse code - this should be thread-safe
                    val result = parser.parse(code)
                    assertThat(result.isSuccessful).isTrue()

                    val unit = result.result.get()

                    // Verify correct parsing (not corrupted by concurrent access)
                    assertThat(unit.types).hasSize(1)
                    assertThat(unit.types[0].name).isEqualTo(className)

                    // Verify comments when present (detects mutable state corruption)
                    unit.types[0].comment?.let { comment ->
                        assertThat(comment.content.trim())
                            .describedAs(
                                "Comment content should match expected for $className (detects state corruption)",
                            )
                            .contains(classComment)
                    }

                    "$threadId-$iteration" to unit
                }
            }
        }

        try {
            // Execute all tasks with a 30-second timeout
            val futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS)

            // Check if any tasks timed out
            val timedOut = futures.any { it.isCancelled }
            assertThat(timedOut)
                .describedAs("Some parsing tasks timed out")
                .isFalse()

            // Collect results - .get() will throw if any task failed
            val results = futures.map { it.get() }
            assertThat(results).hasSize(totalOperations)

            // Spot-check a few results to ensure correctness
            val resultsMap = results.toMap()
            resultsMap["0-0"]?.let { unit ->
                assertThat(unit.types).hasSize(1)
                assertThat(unit.types[0].name).isEqualTo("TestClass0_0")
                unit.types[0].comment?.let { comment ->
                    assertThat(comment.content).contains("Comment for TestClass0_0")
                }
            }
            resultsMap["10-25"]?.let { unit ->
                assertThat(unit.types).hasSize(1)
                assertThat(unit.types[0].name).isEqualTo("TestClass10_25")
                unit.types[0].comment?.let { comment ->
                    assertThat(comment.content).contains("Comment for TestClass10_25")
                }
            }
            resultsMap["19-49"]?.let { unit ->
                assertThat(unit.types).hasSize(1)
                assertThat(unit.types[0].name).isEqualTo("TestClass19_49")
                unit.types[0].comment?.let { comment ->
                    assertThat(comment.content).contains("Comment for TestClass19_49")
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
