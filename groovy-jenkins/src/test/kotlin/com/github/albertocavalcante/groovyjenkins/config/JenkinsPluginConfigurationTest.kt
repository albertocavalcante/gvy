package com.github.albertocavalcante.groovyjenkins.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JenkinsPluginConfigurationTest {

    private val config = JenkinsPluginConfiguration()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parsePluginsFile - parses standard format`() {
        val pluginsFile = tempDir.resolve("plugins.txt")
        Files.writeString(
            pluginsFile,
            """
            workflow-aggregator:2.6
            kubernetes:1.30.0
            docker-workflow:1.28
            # This is a comment
            credentials-binding:1.27
            """.trimIndent(),
        )

        val plugins = config.parsePluginsFile(pluginsFile)

        assertEquals(4, plugins.size)
        assertEquals("workflow-aggregator", plugins[0].shortName)
        assertEquals("2.6", plugins[0].version)
        assertEquals("kubernetes", plugins[1].shortName)
        assertEquals("1.30.0", plugins[1].version)
    }

    @Test
    fun `parsePluginsFile - handles plugins without version`() {
        val pluginsFile = tempDir.resolve("plugins.txt")
        Files.writeString(
            pluginsFile,
            """
            git
            slack:1.0
            """.trimIndent(),
        )

        val plugins = config.parsePluginsFile(pluginsFile)

        assertEquals(2, plugins.size)
        assertEquals("git", plugins[0].shortName)
        assertNull(plugins[0].version)
        assertEquals("slack", plugins[1].shortName)
        assertEquals("1.0", plugins[1].version)
    }

    @Test
    fun `parsePluginsFile - ignores comments and blank lines`() {
        val pluginsFile = tempDir.resolve("plugins.txt")
        Files.writeString(
            pluginsFile,
            """
            # Comment at start
            git:5.0
            
            # Another comment
            
            slack:1.0
            # Trailing comment
            """.trimIndent(),
        )

        val plugins = config.parsePluginsFile(pluginsFile)

        assertEquals(2, plugins.size)
    }

    @Test
    fun `parsePluginsFile - returns empty for non-existent file`() {
        val plugins = config.parsePluginsFile(tempDir.resolve("nonexistent.txt"))
        assertTrue(plugins.isEmpty())
    }

    @Test
    fun `toMavenCoordinates - kubernetes uses correct group`() {
        val plugin = JenkinsPluginConfiguration.PluginEntry("kubernetes", "1.30.0")
        val coords = plugin.toMavenCoordinates()

        assertNotNull(coords)
        assertEquals("org.csanchez.jenkins.plugins", coords.groupId)
        assertEquals("kubernetes", coords.artifactId)
        assertEquals("1.30.0", coords.version)
    }

    @Test
    fun `toMavenCoordinates - workflow plugins use workflow group`() {
        val plugin = JenkinsPluginConfiguration.PluginEntry("workflow-basic-steps", "2.24")
        val coords = plugin.toMavenCoordinates()

        assertNotNull(coords)
        assertEquals("org.jenkins-ci.plugins.workflow", coords.groupId)
        assertEquals("workflow-basic-steps", coords.artifactId)
    }

    @Test
    fun `toMavenCoordinates - returns null without version`() {
        val plugin = JenkinsPluginConfiguration.PluginEntry("git", null)
        assertNull(plugin.toMavenCoordinates())
    }

    @Test
    fun `findPluginsFile - finds in workspace root`() {
        val pluginsFile = tempDir.resolve("plugins.txt")
        Files.writeString(pluginsFile, "git:5.0")

        val found = JenkinsPluginConfiguration.findPluginsFile(tempDir)

        assertNotNull(found)
        assertEquals(pluginsFile, found)
    }

    @Test
    fun `findPluginsFile - finds in jenkins subdirectory`() {
        val jenkinsDir = tempDir.resolve("jenkins")
        Files.createDirectories(jenkinsDir)
        val pluginsFile = jenkinsDir.resolve("plugins.txt")
        Files.writeString(pluginsFile, "git:5.0")

        val found = JenkinsPluginConfiguration.findPluginsFile(tempDir)

        assertNotNull(found)
        assertEquals(pluginsFile, found)
    }

    @Test
    fun `loadPluginsFromWorkspace - loads from standard locations`() {
        val pluginsFile = tempDir.resolve("plugins.txt")
        Files.writeString(
            pluginsFile,
            """
            kubernetes:1.30.0
            docker-workflow:1.28
            """.trimIndent(),
        )

        val plugins = config.loadPluginsFromWorkspace(tempDir)

        assertEquals(2, plugins.size)
        assertEquals("kubernetes", plugins[0].shortName)
    }
}
