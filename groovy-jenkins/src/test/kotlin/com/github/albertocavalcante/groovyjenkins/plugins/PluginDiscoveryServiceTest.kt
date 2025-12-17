package com.github.albertocavalcante.groovyjenkins.plugins

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginDiscoveryServiceTest {

    @TempDir
    lateinit var tempDir: Path

    // ========== Basic plugins.txt Parsing ==========

    @Test
    fun `should parse plugins txt with versions`() {
        createPluginsTxt(
            """
            workflow-basic-steps:2.24
            git:4.11.3
            credentials-binding:1.27
            """.trimIndent(),
        )

        val service = PluginDiscoveryService(tempDir, PluginConfiguration())
        val plugins = service.discoverPlugins()

        assertContainsPlugin(plugins, "workflow-basic-steps", "2.24")
        assertContainsPlugin(plugins, "git", "4.11.3")
        assertContainsPlugin(plugins, "credentials-binding", "1.27")
    }

    @Test
    fun `should handle plugins without versions`() {
        createPluginsTxt(
            """
            some-other-plugin
            git:4.11.3
            """.trimIndent(),
        )

        val service = PluginDiscoveryService(tempDir, PluginConfiguration())
        val plugins = service.discoverPlugins()

        assertContainsPlugin(plugins, "some-other-plugin", null)
        assertContainsPlugin(plugins, "git", "4.11.3")
    }

    @Test
    fun `should ignore comments and blank lines`() {
        createPluginsTxt(
            """
            # This is a comment
            extra-plugin-1:1.0

            # Another comment
            extra-plugin-2:2.0

            """.trimIndent(),
        )

        val service = PluginDiscoveryService(tempDir, PluginConfiguration())
        val plugins = service.discoverPlugins()

        // Should have: 4 defaults + 2 from file = 6 total
        assertEquals(DEFAULT_PLUGIN_COUNT + 2, plugins.size)
        assertTrue(plugins.any { it.shortName == "extra-plugin-1" })
        assertTrue(plugins.any { it.shortName == "extra-plugin-2" })
    }

    // ========== Configuration Path Override ==========

    @Test
    fun `should use configured pluginsTxtPath over auto-discovery`() {
        val customPath = tempDir.resolve("custom/my-plugins.txt")
        Files.createDirectories(customPath.parent)
        Files.writeString(customPath, "custom-plugin:1.0")
        createPluginsTxt("standard-plugin:2.0")

        val config = PluginConfiguration(pluginsTxtPath = customPath.toString())
        val service = PluginDiscoveryService(tempDir, config)
        val names = service.getInstalledPluginNames()

        assertTrue(names.contains("custom-plugin"), "Should find plugin from configured path")
        assertFalse(names.contains("standard-plugin"), "Should NOT find plugin from standard path")
    }

    // ========== Default Plugins ==========

    @Test
    fun `should include default plugins even without plugins txt`() {
        val service = PluginDiscoveryService(tempDir, PluginConfiguration())
        val names = service.getInstalledPluginNames()

        assertTrue(names.contains("workflow-basic-steps"))
        assertTrue(names.contains("workflow-durable-task-step"))
        assertTrue(names.contains("pipeline-model-definition"))
        assertTrue(names.contains("workflow-cps"))
    }

    @Test
    fun `should exclude defaults when includeDefaultPlugins is false`() {
        createPluginsTxt("my-plugin:1.0")

        val config = PluginConfiguration(includeDefaultPlugins = false)
        val service = PluginDiscoveryService(tempDir, config)
        val names = service.getInstalledPluginNames()

        assertTrue(names.contains("my-plugin"))
        assertFalse(names.contains("workflow-basic-steps"))
    }

    // ========== Config Plugins ==========

    @Test
    fun `should merge config plugins with defaults`() {
        val config = PluginConfiguration(
            plugins = listOf("extra-plugin:1.0", "another-plugin:2.0"),
        )
        val service = PluginDiscoveryService(tempDir, config)
        val names = service.getInstalledPluginNames()

        assertTrue(names.contains("workflow-basic-steps"))
        assertTrue(names.contains("extra-plugin"))
        assertTrue(names.contains("another-plugin"))
    }

    @Test
    fun `should merge plugins txt with defaults and config`() {
        createPluginsTxt("file-plugin:1.0")

        val config = PluginConfiguration(plugins = listOf("config-plugin:2.0"))
        val service = PluginDiscoveryService(tempDir, config)
        val names = service.getInstalledPluginNames()

        assertTrue(names.contains("workflow-basic-steps"))
        assertTrue(names.contains("config-plugin"))
        assertTrue(names.contains("file-plugin"))
    }

    // ========== Version Override ==========

    @Test
    fun `plugins txt should override default plugin versions`() {
        createPluginsTxt("workflow-basic-steps:999.0")

        val service = PluginDiscoveryService(tempDir, PluginConfiguration())
        val plugins = service.discoverPlugins()

        val wbs = plugins.find { it.shortName == "workflow-basic-steps" }
        assertEquals("999.0", wbs?.version)
    }

    @Test
    fun `config plugins should override default versions`() {
        val config = PluginConfiguration(plugins = listOf("workflow-basic-steps:888.0"))
        val service = PluginDiscoveryService(tempDir, config)
        val plugins = service.discoverPlugins()

        val wbs = plugins.find { it.shortName == "workflow-basic-steps" }
        assertEquals("888.0", wbs?.version)
    }

    // ========== Edge Cases ==========

    @Test
    fun `should handle extra fields in plugin line`() {
        createPluginsTxt("git:4.11.3:http://example.com/git.jpi")

        val service = PluginDiscoveryService(tempDir, PluginConfiguration())
        val plugins = service.discoverPlugins()

        assertContainsPlugin(plugins, "git", "4.11.3")
    }

    @Test
    fun `should find plugins txt in subdirectories`() {
        val jenkinsDir = tempDir.resolve("jenkins")
        Files.createDirectories(jenkinsDir)
        Files.writeString(jenkinsDir.resolve("plugins.txt"), "subdir-plugin:1.0")

        val service = PluginDiscoveryService(tempDir, PluginConfiguration())
        assertTrue(service.getInstalledPluginNames().contains("subdir-plugin"))
    }

    // ========== Helpers ==========

    private fun createPluginsTxt(content: String) {
        Files.writeString(tempDir.resolve("plugins.txt"), content)
    }

    private fun assertContainsPlugin(
        plugins: List<PluginDiscoveryService.InstalledPlugin>,
        shortName: String,
        version: String?,
    ) {
        val plugin = plugins.find { it.shortName == shortName }
        assertTrue(plugin != null, "Plugin '$shortName' not found. Found: ${plugins.map { it.shortName }}")
        if (version != null) {
            assertEquals(version, plugin.version, "Version mismatch for '$shortName'")
        }
    }

    companion object {
        const val DEFAULT_PLUGIN_COUNT = 4
    }
}
