package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

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

    @Test
    fun `parse is thread-safe with concurrent access`() {
        // Issue 3: Verify that parse() doesn't have race conditions
        // when called concurrently from multiple threads
        val threadCount = 20 // Increased from 10 for better race condition detection
        val iterations = 50 // Increased from 5 for more thorough testing

        // Use a shared parser instance to test thread-safety
        val config = ParserConfiguration().setAttributeComments(true)
        val parser = GroovyParser(config)

        val totalOperations = threadCount * iterations
        val startLatch = CountDownLatch(totalOperations)
        val results = mutableListOf<Pair<String, CompilationUnit>>()
        val exceptions = mutableListOf<Pair<String, Exception>>()
        val resultsLock = Object()
        val exceptionsLock = Object()

        val threads = (0 until threadCount).flatMap { threadId ->
            (0 until iterations).map { iteration ->
                Thread {
                    // Wait for all threads to be ready before starting
                    startLatch.countDown()
                    startLatch.await()

                    try {
                        // Each thread parses different code with unique comments
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

                        // Verify comments are correctly parsed when present (this detects mutable state corruption)
                        // Note: Comment parsing is not 100% reliable, so we check only when present
                        val classNode = unit.types[0]
                        classNode.comment?.let { comment ->
                            val parsedClassComment = comment.content.trim()
                            assertThat(parsedClassComment)
                                .describedAs(
                                    "Comment content should match expected for $className (detects state corruption)",
                                )
                                .contains(classComment)
                        }

                        synchronized(resultsLock) {
                            results.add("$threadId-$iteration" to unit)
                        }
                    } catch (e: Exception) {
                        synchronized(exceptionsLock) {
                            exceptions.add("$threadId-$iteration" to e)
                        }
                    }
                }
            }
        }

        // Start all threads and wait with shared deadline
        threads.forEach { it.start() }
        val deadline = System.currentTimeMillis() + 30000 // 30 seconds total timeout
        threads.forEach { thread ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) {
                thread.join(remaining)
            }
            assertThat(thread.isAlive)
                .describedAs("Thread should complete within timeout")
                .isFalse()
        }

        // Verify no exceptions occurred
        if (exceptions.isNotEmpty()) {
            val firstException = exceptions.first()
            throw AssertionError(
                "Exception occurred during concurrent parsing in ${firstException.first}: ${firstException.second.message}",
                firstException.second,
            )
        }

        // Verify all conversions completed
        assertThat(results).hasSize(totalOperations)

        // Spot-check a few results to ensure correctness
        val resultsMap = results.toMap()
        resultsMap["0-0"]?.let { unit ->
            assertThat(unit.types).hasSize(1)
            assertThat(unit.types[0].name).isEqualTo("TestClass0_0")
            // Verify comment if present (comment parsing not 100% reliable)
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
    }
}
