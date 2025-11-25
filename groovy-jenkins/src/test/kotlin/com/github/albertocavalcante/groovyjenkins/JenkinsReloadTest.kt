package com.github.albertocavalcante.groovyjenkins

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JenkinsReloadTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should identify GDSL files based on extension`() {
        val gdslUri = URI.create("file://$tempDir/test.gdsl")
        val config = JenkinsConfiguration()
        val manager = JenkinsWorkspaceManager(config, tempDir)

        assertTrue(manager.isGdslFile(gdslUri))
    }

    @Test
    fun `should identify GDSL files based on configured patterns`() {
        val customUri = URI.create("file://$tempDir/custom.dsl")
        val config = JenkinsConfiguration(
            gdslPaths = listOf("*.dsl"),
        )
        val manager = JenkinsWorkspaceManager(config, tempDir)

        assertTrue(manager.isGdslFile(customUri))
    }

    @Test
    fun `should not identify non-GDSL files`() {
        val groovyUri = URI.create("file://$tempDir/Script.groovy")
        val config = JenkinsConfiguration()
        val manager = JenkinsWorkspaceManager(config, tempDir)

        assertFalse(manager.isGdslFile(groovyUri))
    }
}
