package com.github.albertocavalcante.diagnostics.codenarc

import com.github.albertocavalcante.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.diagnostics.api.WorkspaceContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test to verify the CodeNarc diagnostic triplication fix.
 * This test ensures that CodeNarc violations are only reported once per occurrence.
 */
class CodeNarcDiagnosticFixTest {

    @Test
    fun `should not triplicate diagnostics when analyzing groovy code with violations`() = runTest {
        // Given: A sample Groovy code with known violations - use trailing whitespace which definitely exists
        val groovyCodeWithViolations = "class TestClass {\n" +
            "    def method() {   \n" + // Line with trailing whitespace
            "        def x = 1\n" +
            "        return x\n" +
            "    }\n" +
            "}"

        // Create a test ruleset that includes TrailingWhitespace rule which definitely exists
        val testRuleset = """
            ruleset {
                description 'Test ruleset for CodeNarc diagnostic fix verification'

                // Include TrailingWhitespace rule which we know exists and will detect our test violations
                TrailingWhitespace

                // Include some basic rules that should definitely exist
                ruleset('rulesets/basic.xml') {
                    include 'EmptyClass'
                    include 'EmptyMethod'
                }
            }
        """.trimIndent()

        // Mock configuration provider that returns our test ruleset
        val workspaceContext = object : WorkspaceContext {
            override val root: Path? = Paths.get(".")
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }

        // Use a custom ruleset resolver for testing
        val testRulesetResolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext): RulesetConfiguration = RulesetConfiguration(
                rulesetContent = testRuleset,
                propertiesFile = null,
                source = "test-ruleset",
            )
        }

        // When: We analyze the code using our test ruleset
        val diagnosticProvider = CodeNarcDiagnosticProvider(workspaceContext, testRulesetResolver)
        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(groovyCodeWithViolations, "TestClass.groovy")

        // Then: Each violation should appear exactly once (no triplication)
        // We expect some diagnostics but they should be unique
        assertTrue(diagnostics.isNotEmpty(), "Should detect at least one diagnostic")

        // Verify no duplicate diagnostics for the same line/rule
        val violationsByLineAndRule = diagnostics.groupBy { "${it.range.start.line}:${it.code.left}" }
        violationsByLineAndRule.forEach { (lineAndRule, violations) ->
            assertEquals(
                1,
                violations.size,
                "Violation $lineAndRule should appear exactly once, but found ${violations.size} times",
            )
        }

        println("✅ CodeNarc diagnostic triplication fix verified!")
        println("   Found ${diagnostics.size} unique diagnostics")
        diagnostics.forEach { diagnostic ->
            println("   - Line ${diagnostic.range.start.line + 1}: ${diagnostic.code.left} - ${diagnostic.message}")
        }
    }

    @Test
    fun `should handle empty source code gracefully`() = runTest {
        val workspaceContext = object : WorkspaceContext {
            override val root: Path? = Paths.get(".")
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }

        val diagnosticProvider = CodeNarcDiagnosticProvider(workspaceContext)
        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics("", "empty.groovy")

        // Should not crash and should return empty diagnostics
        assertTrue(diagnostics.isEmpty(), "Empty source should produce no diagnostics")
    }

    @Test
    fun `should handle basic groovy code without violations`() = runTest {
        val cleanGroovyCode = """
            class CleanClass {
                def cleanMethod() {
                    return "clean"
                }
            }
        """.trimIndent()

        val workspaceContext = object : WorkspaceContext {
            override val root: Path? = Paths.get(".")
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }

        val diagnosticProvider = CodeNarcDiagnosticProvider(workspaceContext)
        val diagnostics = diagnosticProvider.analyzeAndGetDiagnostics(cleanGroovyCode, "CleanClass.groovy")

        // Clean code might still have some style violations, but should not crash
        println("Clean code analysis found ${diagnostics.size} diagnostics")
        diagnostics.forEach { diagnostic ->
            println("  - ${diagnostic.code.left}: ${diagnostic.message}")
        }
    }
}
