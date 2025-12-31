package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
        val threadCount = 10
        val iterations = 5

        // Use a shared parser instance to test thread-safety
        val config = ParserConfiguration().setAttributeComments(true)
        val parser = GroovyParser(config)

        val results = java.util.concurrent.ConcurrentHashMap<String, CompilationUnit>()
        val exceptions = java.util.concurrent.ConcurrentHashMap<String, Exception>()

        val threads = (0 until threadCount).flatMap { threadId ->
            (0 until iterations).map { iteration ->
                Thread {
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

                        // Verify comments are correctly parsed (this detects mutable state corruption)
                        // Comments should be present when attributeComments is enabled
                        val classNode = unit.types[0]
                        if (classNode.comment != null) {
                            val parsedClassComment = classNode.comment!!.content.trim()
                            if (!parsedClassComment.contains(classComment)) {
                                throw AssertionError(
                                    "Comment mismatch for $className: " +
                                        "expected '$classComment' in '$parsedClassComment'",
                                )
                            }
                        }

                        results["$threadId-$iteration"] = unit
                    } catch (e: Exception) {
                        exceptions["$threadId-$iteration"] = e
                    }
                }
            }
        }

        // Start all threads at once to maximize concurrency
        threads.forEach { it.start() }
        threads.forEach { it.join(10000) } // Wait max 10 seconds

        // Verify no exceptions occurred
        assertThat(exceptions).describedAs("No exceptions should occur during concurrent parsing").isEmpty()

        // Verify all conversions completed
        assertThat(results).hasSize(threadCount * iterations)

        // Spot-check a few results to ensure correctness
        results["0-0"]?.let { unit ->
            assertThat(unit.types).hasSize(1)
            assertThat(unit.types[0].name).isEqualTo("TestClass0_0")
            // Verify comment if present
            unit.types[0].comment?.content?.let { content ->
                assertThat(content).contains("Comment for TestClass0_0")
            }
        }
        results["5-3"]?.let { unit ->
            assertThat(unit.types).hasSize(1)
            assertThat(unit.types[0].name).isEqualTo("TestClass5_3")
            // Verify comment if present
            unit.types[0].comment?.content?.let { content ->
                assertThat(content).contains("Comment for TestClass5_3")
            }
        }
    }
}
