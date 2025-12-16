package com.github.albertocavalcante.groovyjenkins.updatecenter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JenkinsUpdateCenterClientTest {

    private val client = JenkinsUpdateCenterClient()

    @Test
    fun `resolvePluginCoordinates - returns known kubernetes coordinates`() {
        val coords = client.resolvePluginCoordinates("kubernetes", "1.30.0")

        assertNotNull(coords)
        assertEquals("org.csanchez.jenkins.plugins", coords.first)
        assertEquals("kubernetes", coords.second)
        assertEquals("1.30.0", coords.third)
    }

    @Test
    fun `resolvePluginCoordinates - returns known workflow-basic-steps coordinates`() {
        val coords = client.resolvePluginCoordinates("workflow-basic-steps", "2.24")

        assertNotNull(coords)
        assertEquals("org.jenkins-ci.plugins.workflow", coords.first)
        assertEquals("workflow-basic-steps", coords.second)
        assertEquals("2.24", coords.third)
    }

    @Test
    fun `resolvePluginCoordinates - returns known git coordinates`() {
        val coords = client.resolvePluginCoordinates("git", "5.0.0")

        assertNotNull(coords)
        assertEquals("org.jenkins-ci.plugins", coords.first)
        assertEquals("git", coords.second)
    }

    @Test
    fun `resolvePluginCoordinates - fallback for unknown plugin`() {
        val coords = client.resolvePluginCoordinates("unknown-plugin", "1.0.0")

        assertNotNull(coords)
        assertEquals("org.jenkins-ci.plugins", coords.first) // Fallback group
        assertEquals("unknown-plugin", coords.second)
        assertEquals("1.0.0", coords.third)
    }

    @Test
    fun `resolvePluginCoordinates - returns correct lockable-resources coordinates`() {
        val coords = client.resolvePluginCoordinates("lockable-resources", "2.0")

        assertNotNull(coords)
        assertEquals("org.6wind.jenkins", coords.first) // Special group
        assertEquals("lockable-resources", coords.second)
    }

    @Test
    fun `resolvePluginCoordinates - returns correct http-request coordinates`() {
        val coords = client.resolvePluginCoordinates("http-request", "1.16")

        assertNotNull(coords)
        assertEquals("org.jenkins-ci.plugins", coords.first)
        assertEquals("http_request", coords.second) // Artifact uses underscore
    }

    @Test
    fun `KNOWN_PLUGIN_GAVS - covers common pipeline plugins`() {
        // Verify we have coordinates for the most common plugins
        val commonPlugins = listOf(
            "kubernetes",
            "docker-workflow",
            "credentials-binding",
            "git",
            "ssh-agent",
            "slack",
            "junit",
        )

        commonPlugins.forEach { plugin ->
            val coords = client.resolvePluginCoordinates(plugin, "1.0.0")
            assertNotNull(coords, "Should have coordinates for $plugin")
        }
    }
}
