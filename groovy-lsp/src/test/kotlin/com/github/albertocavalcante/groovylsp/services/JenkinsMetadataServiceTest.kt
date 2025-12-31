package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovyjenkins.JenkinsConfiguration
import com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager
import com.github.albertocavalcante.groovyjenkins.extraction.PluginDownloader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JenkinsMetadataServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `initialize downloads and registers plugins from plugins file`() = runBlocking {
        // Arrange
        val pluginManager = mockk<JenkinsPluginManager>(relaxed = true)
        val downloader = mockk<PluginDownloader>()

        val pluginsFile = tempDir.resolve("plugins.txt")
        Files.writeString(pluginsFile, "workflow-aggregator:2.6\nkubernetes:1.30.0")

        val config = JenkinsConfiguration(pluginsFile = pluginsFile.toString())

        val service = JenkinsMetadataService(
            pluginManager = pluginManager,
            configuration = config,
            pluginDownloader = downloader,
        )

        val jarPath1 = tempDir.resolve("workflow-aggregator.jar")
        val jarPath2 = tempDir.resolve("kubernetes.jar")

        coEvery { downloader.download("workflow-aggregator", "2.6") } returns jarPath1
        coEvery { downloader.download("kubernetes", "1.30.0") } returns jarPath2

        // Act
        service.initialize()

        // Assert
        coVerify {
            downloader.download("workflow-aggregator", "2.6")
            pluginManager.registerPluginJar("workflow-aggregator", jarPath1)

            downloader.download("kubernetes", "1.30.0")
            pluginManager.registerPluginJar("kubernetes", jarPath2)
        }
    }

    @Test
    fun `initialize does nothing if plugins file is missing`() = runBlocking {
        val pluginManager = mockk<JenkinsPluginManager>(relaxed = true)
        val downloader = mockk<PluginDownloader>()
        val config = JenkinsConfiguration(pluginsFile = "non/existent/path.txt")

        val service = JenkinsMetadataService(
            pluginManager = pluginManager,
            configuration = config,
            pluginDownloader = downloader,
        )

        service.initialize()

        coVerify(exactly = 0) { downloader.download(any(), any()) }
    }
}
