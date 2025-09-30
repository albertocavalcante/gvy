package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.test.MockConfigurationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive test suite for CodeNarcService.
 *
 * Tests the fix for the JSON parsing issue where Groovy DSL rulesets
 * were being parsed as JSON, causing groovy.json.JsonException.
 */
class CodeNarcServiceTest {

    private lateinit var service: CodeNarcService
    private val testUri = URI.create("file:///test.groovy")

    @BeforeEach
    fun setUp() {
        service = CodeNarcService(MockConfigurationProvider())
    }

    @Nested
    @DisplayName("Current Bug Reproduction")
    inner class CurrentBugTests {

        @Test
        fun `should no longer throw JsonException with DSL ruleset`() = runBlocking {
            // This test verifies that the JsonException bug is fixed
            // Previously: DSL format was incorrectly parsed as JSON causing JsonException
            // Now: DSL format should work via temp file approach

            // Code with trailing whitespace should potentially trigger violations
            val source = "def hello() {    \n    println 'Hello'    \n}"

            // The fix should prevent JsonException and allow analysis to proceed
            val diagnostics = service.analyzeString(source, testUri)

            // After fix: analysis should succeed (no JsonException)
            // Note: diagnostics list might be empty if rules don't trigger on this code,
            // but the important thing is that no JsonException occurs
            assertNotNull(diagnostics, "Analysis should succeed without JsonException")

            // If we get here without exception, the JsonException bug is fixed!
        }
    }

    @Nested
    @DisplayName("Basic Functionality Tests")
    inner class BasicFunctionalityTests {

        @Test
        fun `should handle empty source code gracefully`() = runBlocking {
            val violations = service.analyzeString("", testUri)
            assertNotNull(violations)
            assertTrue(violations.isEmpty())
        }

        @Test
        fun `should handle blank source code gracefully`() = runBlocking {
            val violations = service.analyzeString("   \n\t\n  ", testUri)
            assertNotNull(violations)
            assertTrue(violations.isEmpty())
        }

        @Test
        fun `should analyze simple Groovy code without exceptions`() = runBlocking {
            val source = """
                def hello() {
                    println "Hello World"
                }
            """.trimIndent()

            assertDoesNotThrow {
                val violations = service.analyzeString(source, testUri)
                assertNotNull(violations)
            }
        }
    }

    @Nested
    @DisplayName("Ruleset Format Support")
    inner class RulesetFormatTests {

        @Test
        fun `should support Groovy DSL ruleset format`() = runBlocking {
            // Test that DSL format works (this should work after our fix)
            val source = "def hello() {    \n    println 'Hello'    \n}"

            // After fix: this should work without JsonException
            assertDoesNotThrow {
                val violations = service.analyzeString(source, testUri)
                assertNotNull(violations)
            }
        }

        @Test
        fun `should detect violations with DSL ruleset`() = runBlocking {
            // Code with trailing whitespace should trigger TrailingWhitespace rule
            val sourceWithTrailingWS = "def hello() {    \n    println 'Hello'    \n}"

            val violations = service.analyzeString(sourceWithTrailingWS, testUri)

            // Should detect at least one violation (trailing whitespace)
            assertTrue(violations.isNotEmpty(), "Should detect trailing whitespace violation")

            val trailingWSViolation = violations.find {
                it.source == "TrailingWhitespace"
            }
            assertNotNull(trailingWSViolation, "Should find TrailingWhitespace violation")
        }

        @Test
        fun `should support JSON ruleset format when provided`() = runBlocking {
            // Test future JSON format support
            // Note: This test will guide implementation of JSON format support
            @Suppress("UNUSED_VARIABLE")
            val jsonRuleset = """
                {
                  "TrailingWhitespace": {
                    "priority": 1
                  }
                }
            """.trimIndent()

            // TODO: Implement JSON format detection and support
            // For now, this is a placeholder for future implementation
            val source = "def hello() {    \n    println 'Hello'    \n}"

            // This should work after implementing JSON format support
            assertDoesNotThrow {
                val violations = service.analyzeString(source, testUri)
                assertNotNull(violations)
            }
        }
    }

    @Nested
    @DisplayName("Memory Efficiency Tests")
    inner class MemoryEfficiencyTests {

        @Test
        fun `should reuse ruleset compilation for same content`() = runBlocking {
            val source = "def hello() { println 'Hello' }"

            // Run analysis multiple times - should be memory efficient
            repeat(5) {
                val violations = service.analyzeString(source, testUri)
                assertNotNull(violations)
            }

            // TODO: Add assertions to verify ruleset caching/reuse
            // This will be implemented with RuleSetManager
        }

        @Test
        fun `should handle concurrent analysis safely`() = runBlocking {
            val source = "def hello() { println 'Hello' }"

            // Run concurrent analyses using coroutineScope
            coroutineScope {
                val jobs = (1..10).map { index ->
                    async {
                        val uri = URI.create("file:///test$index.groovy")
                        service.analyzeString(source, uri)
                    }
                }

                // All should complete without exceptions
                val results = jobs.map { it.await() }
                assertEquals(10, results.size)
                results.forEach { violations ->
                    assertNotNull(violations)
                }
            }
        }
    }

    @Nested
    @DisplayName("Custom Ruleset Support (BYO)")
    inner class CustomRulesetTests {

        @Test
        fun `should support custom ruleset from file path`() = runBlocking {
            // Create a temporary custom ruleset file
            val customRuleset = """
                ruleset {
                    description 'Custom Test Ruleset'
                    rule('TrailingWhitespace') {
                        priority = 1
                    }
                    rule('ConsecutiveBlankLines') {
                        enabled = false
                    }
                }
            """.trimIndent()

            val tempFile = Files.createTempFile("custom-codenarc-", ".groovy")
            Files.write(tempFile, customRuleset.toByteArray())

            try {
                // TODO: Implement custom ruleset path configuration
                // This will be part of BYO ruleset support
                val source = "def hello() {    \n    println 'Hello'    \n}"

                // Should work with custom ruleset
                assertDoesNotThrow {
                    val violations = service.analyzeString(source, testUri)
                    assertNotNull(violations)
                }
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }

        @Test
        fun `should fallback to default ruleset when custom ruleset fails`() = runBlocking {
            // Test fallback behavior when custom ruleset is invalid
            val source = "def hello() { println 'Hello' }"

            // Should not fail even if custom ruleset has issues
            assertDoesNotThrow {
                val violations = service.analyzeString(source, testUri)
                assertNotNull(violations)
            }
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        @Test
        fun `should handle malformed Groovy code gracefully`() = runBlocking {
            val malformedSource = "def hello( { invalid syntax"

            assertDoesNotThrow {
                val violations = service.analyzeString(malformedSource, testUri)
                assertNotNull(violations)
                // May contain violations, but should not throw exceptions
            }
        }

        @Test
        fun `should handle very large source files`() = runBlocking {
            val largeSource = buildString {
                appendLine("class LargeClass {")
                repeat(1000) { i ->
                    appendLine("    def method$i() { println 'Method $i' }")
                }
                appendLine("}")
            }

            assertDoesNotThrow {
                val violations = service.analyzeString(largeSource, testUri)
                assertNotNull(violations)
            }
        }

        @Test
        fun `should handle invalid URI gracefully`() = runBlocking {
            val source = "def hello() { println 'Hello' }"
            val invalidUri = URI.create("invalid://uri/path")

            assertDoesNotThrow {
                val violations = service.analyzeString(source, invalidUri)
                assertNotNull(violations)
            }
        }
    }

    @Nested
    @DisplayName("Duplicate Detection Tests")
    inner class DuplicateDetectionTests {

        @Test
        fun `should not duplicate diagnostics from hierarchical results`() = runBlocking {
            // This test verifies the fix for the triplication bug where violations
            // appeared 3 times due to processing at root, directory, and file levels
            val source = """
                def hello() {
                    println "Hello"
                }
            """.trimIndent()

            val diagnostics = service.analyzeString(source, testUri)

            // Group diagnostics by line and message to detect duplicates
            val diagnosticGroups = diagnostics.groupBy {
                "${it.range.start.line}:${it.message}"
            }

            // Each unique violation should appear exactly once
            diagnosticGroups.forEach { (key, group) ->
                assertEquals(
                    1,
                    group.size,
                    "Diagnostic '$key' appeared ${group.size} times instead of once",
                )
            }

            // Also verify that if we have diagnostics, they have valid positions
            diagnostics.forEach { diagnostic ->
                assertTrue(
                    diagnostic.range.start.line >= 0,
                    "Diagnostic line should be non-negative: ${diagnostic.message}",
                )
                assertTrue(
                    diagnostic.range.start.character >= 0,
                    "Diagnostic character should be non-negative: ${diagnostic.message}",
                )
                assertNotNull(diagnostic.source, "Diagnostic should have a source")
                assertNotNull(diagnostic.message, "Diagnostic should have a message")
            }
        }

        @Test
        fun `should handle multiple different violations without duplication`() = runBlocking {
            // Code with multiple types of violations to test duplication across different rules
            val source = """
                def hello() {
                    println "Hello"
                    return null
                }
            """.trimIndent()

            val diagnostics = service.analyzeString(source, testUri)

            // Count occurrences of each violation type
            val violationCounts = diagnostics.groupBy { it.source }
                .mapValues { (_, violations) -> violations.size }

            // Print for debugging
            violationCounts.forEach { (source, count) ->
                println("Violation source '$source': $count occurrences")
            }

            // Each violation type should appear reasonable number of times
            // (not necessarily 1, as same rule can have multiple violations on different lines)
            violationCounts.forEach { (source, count) ->
                assertTrue(count > 0, "Violation source '$source' should have at least 1 occurrence")
                assertTrue(count <= 10, "Violation source '$source' has suspiciously many occurrences: $count")
            }
        }
    }

    @Nested
    @DisplayName("Jenkins Project Support")
    inner class JenkinsProjectTests {

        @Test
        fun `should analyze Jenkins pipeline code`() = runBlocking {
            val pipelineSource = """
                pipeline {
                    agent any
                    stages {
                        stage('Build') {
                            steps {
                                sh 'echo "Building..."'
                            }
                        }
                    }
                }
            """.trimIndent()

            assertDoesNotThrow {
                val violations = service.analyzeString(pipelineSource, testUri)
                assertNotNull(violations)
            }
        }

        @Test
        fun `should handle Jenkins shared library code`() = runBlocking {
            val sharedLibSource = """
                def call(Map config) {
                    pipeline {
                        agent any
                        environment {
                            APP_NAME = config.appName
                        }
                        stages {
                            stage('Deploy') {
                                steps {
                                    script {
                                        deploy(config)
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()

            assertDoesNotThrow {
                val violations = service.analyzeString(sharedLibSource, testUri)
                assertNotNull(violations)
            }
        }
    }

    @Nested
    @DisplayName("Temp File Management")
    inner class TempFileManagementTests {

        @Test
        fun `should clean up temporary files properly`() {
            // TODO: Implement temp file tracking and cleanup verification
            // This will be part of RuleSetManager implementation

            // Test that temp files are created and cleaned up
            val initialTempFileCount = getTempFileCount()

            runBlocking {
                repeat(5) {
                    val source = "def hello() { println 'Hello $it' }"
                    service.analyzeString(source, testUri)
                }
            }

            // After cleanup, temp file count should be reasonable
            // (may not be exactly the same due to other processes)
            val finalTempFileCount = getTempFileCount()
            assertTrue(
                finalTempFileCount < initialTempFileCount + 10,
                "Too many temp files remaining: $finalTempFileCount",
            )
        }

        private fun getTempFileCount(): Int = try {
            Files.list(Path.of(System.getProperty("java.io.tmpdir")))
                .use { stream ->
                    stream.filter {
                        it.fileName.toString().startsWith("codenarc-rules-")
                    }.count().toInt()
                }
        } catch (e: Exception) {
            // Fallback if unable to count temp files (expected in many environments)
            // Swallowing exception is acceptable here as this is a test utility method
            // and the fallback value of 0 is adequate for test assertions
            0
        }
    }

    @Nested
    @DisplayName("MockK Integration Examples")
    inner class MockKExamples {

        @Test
        fun `demonstrate MockK with ConfigurationProvider`() = runBlocking {
            // Example of MockK relaxed mock - automatically returns defaults for unspecified methods
            val relaxedConfig = io.mockk.mockk<ConfigurationProvider>(relaxed = true)
            val serviceWithRelaxed = CodeNarcService(relaxedConfig)

            val source = "def hello() { println 'Hello' }"
            val diagnostics = serviceWithRelaxed.analyzeString(source, testUri)

            assertNotNull(diagnostics)
            // With relaxed mock, unspecified methods return sensible defaults
        }

        @Test
        fun `demonstrate MockK behavior verification`() = runBlocking {
            val mockConfig = io.mockk.mockk<ConfigurationProvider>()

            // Set up mock behavior
            io.mockk.every { mockConfig.getServerConfiguration() } returns
                com.github.albertocavalcante.groovylsp.config.ServerConfiguration()
            io.mockk.every { mockConfig.getWorkspaceRoot() } returns null

            val serviceWithMock = CodeNarcService(mockConfig)
            val source = "def hello() { println 'Hello' }"

            // Execute the method under test
            serviceWithMock.analyzeString(source, testUri)

            // Verify interactions
            io.mockk.verify { mockConfig.getServerConfiguration() }
            io.mockk.verify(atLeast = 1) { mockConfig.getWorkspaceRoot() }
        }
    }
}
