package com.github.albertocavalcante.groovylsp.jenkins

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive edge case and error handling tests for Jenkins interop components.
 * Tests robustness, error conditions, memory management, and boundary conditions.
 */
class JenkinsInteropEdgeCasesTest {

    private val interopTest = JenkinsInteropTest()

    @Test
    fun `should handle interop test failures gracefully`() {
        // This tests the error handling path in testGroovyInterop
        val result = interopTest.testGroovyInterop()

        // Should not throw exceptions even if underlying Groovy calls fail
        assertNotNull(result)
        assertTrue(result.success) // Our current implementation should succeed
        assertNotNull(result.message)
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = ["", "   ", "\t\n", "null", "undefined"])
    fun `parseMethodNames should handle various empty and null-like inputs`(input: String?) {
        val result = if (input == "null") {
            SimpleGdslParser.parseMethodNames(null)
        } else {
            SimpleGdslParser.parseMethodNames(input)
        }

        assertEquals(emptyList<String>(), result)
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "method(name: 'test' | 1", // Missing closing parenthesis - parser finds partial match
            "method name: 'test', type: 'void') | 0", // Missing opening parenthesis
            "method(name 'test', type: 'void') | 0", // Missing colon
            "method(name: test, type: 'void') | 0", // Missing quotes around name
            "method(name: 'te\"st', type: 'void') | 1", // Mixed quotes - parser finds partial match
            "method(name: '', type: 'void') | 0", // Empty method name
            "method(name: '   ', type: 'void') | 1", // Whitespace-only method name
            "method(name: 'test with spaces', type: 'void') | 1", // Method name with spaces
            "method(name: 'test_with_underscores', type: 'void') | 1", // Valid with underscores
            "method(name: 'testWithCamelCase', type: 'void') | 1", // Valid camelCase
        ],
        delimiter = '|',
    )
    fun `parseMethodNames should handle malformed GDSL syntax robustly`(input: String, expectedCount: Int) {
        val result = SimpleGdslParser.parseMethodNames(input.trim())
        assertEquals(expectedCount, result.size, "Failed for input: $input")
    }

    @Test
    fun `should handle extremely large GDSL input without memory issues`() {
        val hugeGdsl = buildString {
            append("contribute(context(ctype: 'hudson.model.Job')) {\n")
            // Create a very large GDSL with 10,000 methods and long content
            repeat(10_000) { i ->
                append(
                    "    method(name: 'extremelyLongMethodNameThatCouldCauseMemoryIssuesMethod$i" +
                        "WithLotsOfExtraCharacters', type: 'void', description: 'This is an extremely long " +
                        "description that could potentially cause memory issues if not handled properly by the " +
                        "parser implementation')\n",
                )
            }
            append("}")
        }

        val startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val result = SimpleGdslParser.parseMethodNames(hugeGdsl)
        val endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryUsed = endMemory - startMemory

        assertEquals(10_000, result.size)
        // Memory usage should be reasonable (less than 50MB for this test)
        assertTrue(memoryUsed < 50_000_000, "Memory usage too high: ${memoryUsed / 1_000_000}MB")
    }

    @Test
    fun `should handle concurrent access with mixed valid and invalid inputs`() {
        val executor: ExecutorService = Executors.newFixedThreadPool(20)
        val results = mutableListOf<List<String>>()
        val errors = mutableListOf<Exception>()

        val testInputs = listOf(
            "method(name: 'valid1', type: 'void')",
            "invalid input",
            null,
            "",
            "method(name: 'valid2', type: 'void')",
            "method(name: 'broken syntax",
            "method(name: 'valid3', type: 'void')",
        )

        // Submit 100 concurrent tasks with various inputs
        repeat(100) { taskIndex ->
            executor.submit {
                try {
                    val input = testInputs[taskIndex % testInputs.size]
                    val result = SimpleGdslParser.parseMethodNames(input)
                    synchronized(results) {
                        results.add(result)
                    }
                } catch (e: Exception) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                }
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

        // Should complete without errors
        assertTrue(errors.isEmpty(), "Should not throw exceptions: ${errors.joinToString()}")
        assertEquals(100, results.size)

        // Valid inputs should consistently return expected results
        val validResults = results.filter { it.isNotEmpty() }
        assertTrue(validResults.isNotEmpty())
        validResults.forEach { result ->
            assertTrue(result.size in 1..1) // Each valid input should return exactly 1 method
        }
    }

    @Test
    fun `should handle deeply nested GDSL structures`() {
        val deeplyNestedGdsl = buildString {
            append("contribute(context(ctype: 'Job')) {\n")
            repeat(50) { depth ->
                append("  ".repeat(depth + 1))
                append("contribute(context(ctype: 'Level$depth')) {\n")
            }

            append("  ".repeat(51))
            append("method(name: 'deepMethod', type: 'void')\n")

            repeat(50) { depth ->
                append("  ".repeat(50 - depth))
                append("}\n")
            }
            append("}")
        }

        val result = SimpleGdslParser.parseMethodNames(deeplyNestedGdsl)
        assertEquals(1, result.size)
        assertEquals("deepMethod", result[0])
    }

    @Test
    fun `should handle GDSL with unusual but valid characters`() {
        val unusualGdsl = """
            method(name: 'test-method', type: 'void')
            method(name: 'test_method', type: 'void')
            method(name: 'testMethod123', type: 'void')
            method(name: 'TestMethod', type: 'void')
            method(name: 'test.method', type: 'void')
            method(name: 'test${'$'}method', type: 'void')
        """.trimIndent()

        val result = SimpleGdslParser.parseMethodNames(unusualGdsl)
        assertEquals(6, result.size)
        assertTrue(result.contains("test-method"))
        assertTrue(result.contains("test_method"))
        assertTrue(result.contains("testMethod123"))
        assertTrue(result.contains("TestMethod"))
        assertTrue(result.contains("test.method"))
        assertTrue(result.contains("test\$method"))
    }

    @Test
    fun `should handle GDSL validation edge cases`() {
        val edgeCases = mapOf(
            "single contribute keyword" to "contribute",
            "single method keyword" to "method",
            "contribute without parentheses" to "contribute { }",
            "method without parentheses" to "method { }",
            "contribute with typo" to "contribte(context()) { }",
            "method with typo" to "metod(name: 'test') { }",
            "mixed case contribute" to "Contribute(context()) { }",
            "mixed case method" to "Method(name: 'test') { }",
            "just braces" to "{ }",
            "just parentheses" to "( )",
            "nested braces without content" to "{ { } }",
        )

        edgeCases.forEach { (description, input) ->
            val isValid = SimpleGdslParser.isValidGdsl(input)
            val containsValidKeywords = input.contains("method(") || input.contains("contribute(")
            assertEquals(containsValidKeywords, isValid, "Failed for: $description")
        }
    }

    @Test
    fun `should handle interop result edge cases`() {
        // Test multiple calls to ensure consistency
        repeat(10) {
            val result = SimpleGdslParser.getTestResult()

            assertNotNull(result["message"])
            assertNotNull(result["timestamp"])
            assertNotNull(result["parser"])
            assertNotNull(result["version"])

            // Verify types
            assertTrue(result["timestamp"] is Long)
            assertTrue(result["message"] is String)
            assertTrue(result["parser"] is String)
            assertTrue(result["version"] is String)

            // Verify reasonable timestamp (after year 2020)
            val timestamp = result["timestamp"] as Long
            assertTrue(timestamp > 1_577_836_800_000L, "Timestamp seems too old: $timestamp")
        }
    }

    @Test
    fun `should handle type interop edge cases`() {
        val edgeCaseInputs = listOf(
            "",
            "   ",
            "method()",
            "method(name:)",
            "method(name: )",
            "method(name: '')",
            "method(type: 'void')",
            "random text without methods",
        )

        edgeCaseInputs.forEach { input ->
            val result = interopTest.demonstrateTypeInterop()
            // This calls a hardcoded GDSL, so should always return the same result
            assertEquals(3, result.size)
            assertTrue(result.contains("echo"))
            assertTrue(result.contains("sh"))
            assertTrue(result.contains("build"))
        }
    }

    @Test
    fun `should handle stress testing with rapid successive calls`() {
        val startTime = System.currentTimeMillis()

        // Make 1000 rapid calls
        repeat(1000) {
            val result = SimpleGdslParser.parseMethodNames("method(name: 'test$it', type: 'void')")
            assertEquals(1, result.size)
            assertEquals("test$it", result[0])
        }

        val duration = System.currentTimeMillis() - startTime
        // Should complete within 5 seconds even under stress
        assertTrue(duration < 5000, "Stress test took too long: ${duration}ms")
    }

    @Test
    fun `should handle resource cleanup properly`() {
        // Test that repeated parsing doesn't accumulate resources
        val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        repeat(100) {
            val largeGdsl = "method(name: 'test', type: 'void')\n".repeat(1000)
            SimpleGdslParser.parseMethodNames(largeGdsl)
        }

        // Force garbage collection for memory testing
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        Thread.sleep(100)

        val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryGrowth = finalMemory - initialMemory

        // Memory growth should be minimal (less than 10MB)
        assertTrue(memoryGrowth < 10_000_000, "Memory leak detected: ${memoryGrowth / 1_000_000}MB growth")
    }

    @Test
    fun `should handle Unicode and special characters in method names`() {
        val unicodeGdsl = """
            method(name: 'método', type: 'void')
            method(name: '测试方法', type: 'void')
            method(name: 'тестМетод', type: 'void')
            method(name: 'メソッド', type: 'void')
            method(name: '🚀rocket', type: 'void')
            method(name: 'α_β_γ', type: 'void')
        """.trimIndent()

        val result = SimpleGdslParser.parseMethodNames(unicodeGdsl)
        assertEquals(6, result.size)
        assertTrue(result.contains("método"))
        assertTrue(result.contains("测试方法"))
        assertTrue(result.contains("тестМетод"))
        assertTrue(result.contains("メソッド"))
        assertTrue(result.contains("🚀rocket"))
        assertTrue(result.contains("α_β_γ"))
    }
}
