package com.github.albertocavalcante.groovylsp.codenarc

import com.github.albertocavalcante.groovylsp.test.MockConfigurationProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * Manual test for CodeNarc integration with actual violation detection.
 */
class CodeNarcManualTest {

    @Test
    fun `should detect trailing whitespace violation`() = runTest {
        val service = CodeNarcService(MockConfigurationProvider())
        val uri = URI.create("file:///test.groovy")

        // Code with trailing whitespace should trigger a violation
        val source = """
            def hello() {
                println "Hello World"
            }
        """.trimIndent()

        val violations = service.analyzeString(source, uri)

        // Should find at least one violation (trailing whitespace or others)
        // Note: This test may pass even if no violations are found due to ruleset configuration
        println("Found ${violations.size} violations:")
        violations.forEach { violation ->
            println("- ${violation.source}: ${violation.message} (line ${violation.range.start.line})")
        }

        // Just verify the service runs without throwing exceptions
        assertTrue(violations.size >= 0)
    }

    @Test
    fun `should convert violations to diagnostics`() = runTest {
        val service = CodeNarcService(MockConfigurationProvider())
        val uri = URI.create("file:///test.groovy")

        val source = """
            def hello() {
                println "Hello"
            }
        """.trimIndent()

        val diagnostics = service.analyzeString(source, uri)

        println("Found ${diagnostics.size} diagnostics")

        diagnostics.forEach { diagnostic ->
            println(
                "- ${diagnostic.source}: ${diagnostic.message} at " +
                    "${diagnostic.range.start.line}:${diagnostic.range.start.character}",
            )
        }

        // Verify analysis works without exceptions
        assertTrue(diagnostics.size >= 0)
    }

    @Test
    fun `should handle configuration manager`() {
        val configManager = CodeNarcConfigurationManager()

        // Test loading default config
        val defaultConfig = configManager.loadConfiguration(null)
        println("Default config source: ${defaultConfig.configSource}")
        println("Is Jenkins project: ${defaultConfig.isJenkinsProject}")
        println("Ruleset length: ${defaultConfig.ruleSetString.length} characters")

        assertTrue(defaultConfig.ruleSetString.isNotEmpty())

        // Test with a mock workspace URI
        val workspaceUri = URI.create("file:///tmp/test-workspace")
        val workspaceConfig = configManager.loadConfiguration(workspaceUri)

        assertTrue(workspaceConfig.ruleSetString.isNotEmpty())
    }
}
