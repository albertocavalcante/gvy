package com.github.albertocavalcante.diagnostics.codenarc

import com.github.albertocavalcante.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.diagnostics.api.WorkspaceContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for HierarchicalRulesetResolver focusing on ruleset resolution and fallback behavior.
 */
class RulesetResolverTest {

    private val resolver = HierarchicalRulesetResolver()

    @Nested
    inner class ClasspathResourceLoaderTest {

        @Test
        fun `should load existing resource from classpath`() {
            val loader = ClasspathResourceLoader()

            val content = loader.load("codenarc/rulesets/base/default.groovy")

            assertNotNull(content)
            assertTrue(content.contains("ruleset"), "Should contain ruleset definition")
        }

        @Test
        fun `should return null for non-existent resource`() {
            val loader = ClasspathResourceLoader()

            val content = loader.load("non/existent/path.groovy")

            assertEquals(null, content)
        }
    }

    @Test
    fun `should resolve Jenkins ruleset for Jenkins project`(@TempDir tempDir: Path) {
        // Given: Jenkins project structure
        Files.createFile(tempDir.resolve("Jenkinsfile"))

        val context = createWorkspaceContext(tempDir, enabled = true)
        val config = resolver.resolve(context)

        // Then: Should load Jenkins ruleset from classpath resource
        assertNotNull(config.rulesetContent)
        assertEquals(
            "resource:codenarc/rulesets/frameworks/jenkins.groovy",
            config.source,
            "Expected Jenkins ruleset resource",
        )
        assertTrue(
            config.rulesetContent.contains("ruleset('rulesets/jenkins.xml')"),
            "Ruleset should reference Jenkins CPS rules",
        )
    }

    @Test
    fun `should resolve default ruleset for plain Groovy project`(@TempDir tempDir: Path) {
        // Given: Plain Groovy project (no indicators)
        val context = createWorkspaceContext(tempDir, enabled = true)
        val config = resolver.resolve(context)

        // Then: Should load default ruleset from classpath resource
        assertNotNull(config.rulesetContent)
        assertEquals(
            "resource:codenarc/rulesets/base/default.groovy",
            config.source,
            "Expected default ruleset resource",
        )
    }

    @Test
    fun `should always resolve a valid ruleset for any project type`(@TempDir tempDir: Path) {
        // Given: Jenkins project
        Files.createFile(tempDir.resolve("Jenkinsfile"))

        val context = createWorkspaceContext(tempDir, enabled = true)
        val config = resolver.resolve(context)

        // Then: Should resolve a valid ruleset (our custom DSL is on the classpath)
        assertNotNull(config.rulesetContent)
        assertTrue(config.rulesetContent.isNotEmpty(), "Ruleset content should not be empty")
        // Should contain ruleset definition
        assertTrue(
            config.rulesetContent.contains("ruleset"),
            "Ruleset should contain ruleset definition",
        )
        // Source should indicate where it came from
        assertTrue(
            config.source.isNotEmpty(),
            "Source should be set",
        )
    }

    @Nested
    inner class FallbackBehavior {

        @Test
        fun `should fallback to bundled XML ruleset when custom resources unavailable`(@TempDir tempDir: Path) {
            // Given: A resource loader that returns nothing (simulates missing resources)
            val emptyResourceLoader = mockk<ResourceLoader> {
                every { load(any()) } returns null
            }
            val resolverWithNoResources = HierarchicalRulesetResolver(
                resourceLoader = emptyResourceLoader,
            )

            val context = createWorkspaceContext(tempDir, enabled = true)
            val config = resolverWithNoResources.resolve(context)

            // Then: Should generate bundled wrapper for basic.xml
            assertNotNull(config.rulesetContent)
            assertTrue(config.source.startsWith("bundled:"), "Expected bundled source, got: ${config.source}")
            assertTrue(
                config.rulesetContent.contains("rulesets/basic.xml"),
                "Should fallback to basic.xml wrapper",
            )
        }

        @Test
        fun `should fallback to bundled Jenkins XML when Jenkins resources unavailable`(@TempDir tempDir: Path) {
            // Given: Jenkins project with a resource loader that returns nothing
            Files.createFile(tempDir.resolve("Jenkinsfile"))

            val emptyResourceLoader = mockk<ResourceLoader> {
                every { load(any()) } returns null
            }
            val resolverWithNoResources = HierarchicalRulesetResolver(
                resourceLoader = emptyResourceLoader,
            )

            val context = createWorkspaceContext(tempDir, enabled = true)
            val config = resolverWithNoResources.resolve(context)

            // Then: Should generate bundled wrapper for jenkins.xml
            assertNotNull(config.rulesetContent)
            assertTrue(config.source.startsWith("bundled:"), "Expected bundled source, got: ${config.source}")
            assertTrue(
                config.rulesetContent.contains("rulesets/jenkins.xml"),
                "Should fallback to jenkins.xml wrapper for Jenkins projects",
            )
        }

        @Test
        fun `should fallback to default ruleset when project-specific resource unavailable`(@TempDir tempDir: Path) {
            // Given: Jenkins project where only default.groovy is available
            Files.createFile(tempDir.resolve("Jenkinsfile"))

            val defaultRulesetContent = """
                ruleset {
                    description 'Default ruleset'
                    ruleset('rulesets/basic.xml')
                }
            """.trimIndent()

            val partialResourceLoader = mockk<ResourceLoader> {
                // Jenkins-specific ruleset not found
                every { load("codenarc/rulesets/frameworks/jenkins.groovy") } returns null
                // But default is available
                every { load("codenarc/rulesets/base/default.groovy") } returns defaultRulesetContent
            }
            val resolver = HierarchicalRulesetResolver(resourceLoader = partialResourceLoader)

            val context = createWorkspaceContext(tempDir, enabled = true)
            val config = resolver.resolve(context)

            // Then: Should fallback to default.groovy
            assertNotNull(config.rulesetContent)
            assertTrue(
                config.source.contains("default.groovy"),
                "Should fallback to default.groovy. Got: ${config.source}",
            )
        }

        @Test
        fun `bundled wrapper should have valid DSL structure`(@TempDir tempDir: Path) {
            // Given: No resources available
            val emptyResourceLoader = mockk<ResourceLoader> {
                every { load(any()) } returns null
            }
            val resolver = HierarchicalRulesetResolver(resourceLoader = emptyResourceLoader)

            val context = createWorkspaceContext(tempDir, enabled = true)
            val config = resolver.resolve(context)

            // Then: Bundled wrapper should be valid DSL
            assertTrue(config.rulesetContent.contains("ruleset {"), "Should start with ruleset block")
            assertTrue(config.rulesetContent.contains("description"), "Should have description")
            assertTrue(config.rulesetContent.contains("ruleset('rulesets/"), "Should reference bundled ruleset")
        }
    }

    @Test
    fun `should use workspace codenarc file when present`(@TempDir tempDir: Path) {
        // Given: Workspace with .codenarc file
        val customRuleset = """
            ruleset {
                description 'Custom workspace ruleset'
                ruleset('rulesets/basic.xml')
            }
        """.trimIndent()
        Files.writeString(tempDir.resolve(".codenarc"), customRuleset)

        val context = createWorkspaceContext(tempDir, enabled = true)
        val config = resolver.resolve(context)

        // Then: Should use workspace file
        assertTrue(config.source.contains("workspace"), "Expected workspace ruleset, got: ${config.source}")
        assertEquals(customRuleset, config.rulesetContent)
    }

    @Test
    fun `should resolve properties file when auto-detect enabled`(@TempDir tempDir: Path) {
        // Given: Workspace with codenarc.properties
        val propertiesContent = "codenarc.propertiesFile=test.properties\n"
        Files.writeString(tempDir.resolve("codenarc.properties"), propertiesContent)

        val context = createWorkspaceContext(tempDir, enabled = true, autoDetect = true)
        val config = resolver.resolve(context)

        // Then: Should find properties file
        assertNotNull(config.propertiesFile)
        assertTrue(config.propertiesFile!!.contains("codenarc.properties"))
    }

    @Test
    fun `should not resolve properties file when auto-detect disabled`(@TempDir tempDir: Path) {
        // Given: Workspace with codenarc.properties but auto-detect disabled
        Files.writeString(tempDir.resolve("codenarc.properties"), "test=value\n")

        val context = createWorkspaceContext(tempDir, enabled = true, autoDetect = false)
        val config = resolver.resolve(context)

        // Then: Should not find properties file
        assertEquals(null, config.propertiesFile)
    }

    @Test
    fun `should handle null workspace root gracefully`() {
        // Given: Context without workspace root
        val context = object : WorkspaceContext {
            override val root: Path? = null
            override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
                override val isEnabled: Boolean = true
                override val propertiesFile: String? = null
                override val autoDetectConfig: Boolean = false
            }
        }

        val config = resolver.resolve(context)

        // Then: Should still resolve (uses PlainGroovy default)
        assertNotNull(config.rulesetContent)
        assertTrue(config.rulesetContent.isNotEmpty())
    }

    @Test
    fun `should include Jenkins CPS rules in Jenkins ruleset`(@TempDir tempDir: Path) {
        // Given: Jenkins project
        Files.createFile(tempDir.resolve("Jenkinsfile"))

        val context = createWorkspaceContext(tempDir, enabled = true)
        val config = resolver.resolve(context)

        // Then: Ruleset should reference Jenkins CPS rules
        assertNotNull(config.rulesetContent)
        // Verify the ruleset includes the bundled Jenkins rules
        assertTrue(
            config.rulesetContent.contains("rulesets/jenkins.xml"),
            "Jenkins ruleset should include rulesets/jenkins.xml for CPS rules. Content: ${config.rulesetContent.take(
                300,
            )}",
        )
        // Verify source indicates Jenkins framework
        assertTrue(
            config.source.contains("jenkins"),
            "Source should indicate Jenkins framework: ${config.source}",
        )
    }

    @Test
    fun `Jenkins ruleset should include ONLY Jenkins-specific rules (not generic rules)`(@TempDir tempDir: Path) {
        // Given: Jenkins project
        Files.createFile(tempDir.resolve("Jenkinsfile"))

        val context = createWorkspaceContext(tempDir, enabled = true)
        val config = resolver.resolve(context)

        // Then: Jenkins ruleset should NOT include generic rulesets
        assertNotNull(config.rulesetContent)

        // ❌ Should NOT include generic rulesets (these cause noise)
        // Note: Check for actual ruleset() directives, not comments
        assertTrue(
            !config.rulesetContent.contains("ruleset('rulesets/basic.xml')"),
            "Jenkins ruleset should NOT include ruleset('rulesets/basic.xml') directive (causes ~30 style warnings). " +
                "Only Jenkins CPS rules should be included.",
        )
        assertTrue(
            !config.rulesetContent.contains("ruleset('rulesets/imports.xml')"),
            "Jenkins ruleset should NOT include ruleset('rulesets/imports.xml') directive (import organization is not critical for Jenkinsfiles).",
        )
        assertTrue(
            !config.rulesetContent.contains("ruleset('rulesets/formatting.xml')"),
            "Jenkins ruleset should NOT include ruleset('rulesets/formatting.xml') directive " +
                "(includes Indentation rule that caused 15 warnings). " +
                "Format-on-save should handle formatting instead.",
        )

        // ✅ Should ONLY include Jenkins CPS rules
        assertTrue(
            config.rulesetContent.contains("rulesets/jenkins.xml"),
            "Jenkins ruleset should include rulesets/jenkins.xml for CPS safety rules.",
        )

        // ✅ Should include critical correctness rules (defined inline, not from bundled rulesets)
        assertTrue(
            config.rulesetContent.contains("CatchException") ||
                config.rulesetContent.contains("CatchThrowable") ||
                config.rulesetContent.contains("UnusedVariable"),
            "Jenkins ruleset should include critical correctness rules defined inline (not from bundled rulesets).",
        )
    }

    // Helper functions

    private fun createWorkspaceContext(
        workspaceRoot: Path,
        enabled: Boolean = true,
        autoDetect: Boolean = true,
        propertiesFile: String? = null,
    ): WorkspaceContext = object : WorkspaceContext {
        override val root: Path? = workspaceRoot
        override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
            override val isEnabled: Boolean = enabled
            override val propertiesFile: String? = propertiesFile
            override val autoDetectConfig: Boolean = autoDetect
        }
    }
}
