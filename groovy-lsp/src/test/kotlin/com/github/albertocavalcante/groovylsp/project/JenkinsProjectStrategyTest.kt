package com.github.albertocavalcante.groovylsp.project

import com.github.albertocavalcante.groovyjenkins.JenkinsConfiguration
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class JenkinsProjectStrategyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var coroutineScope: CoroutineScope
    private lateinit var strategy: JenkinsProjectStrategy

    @BeforeEach
    fun setUp() {
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        strategy = JenkinsProjectStrategy(coroutineScope)
    }

    @AfterEach
    fun tearDown() {
        strategy.shutdown()
        coroutineScope.cancel()
    }

    @Test
    fun `strategy has correct id and display name`() {
        assertEquals("jenkins", strategy.id)
        assertEquals("Jenkins Pipeline", strategy.displayName)
    }

    @Test
    fun `strategy has high priority`() {
        assertEquals(100, strategy.priority)
    }

    @Test
    fun `canHandle returns true when file patterns configured`() {
        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))

        assertTrue(strategy.canHandle(tempDir, config))
    }

    @Test
    fun `canHandle returns false when no file patterns`() {
        val config = createConfig(filePatterns = emptyList())

        assertFalse(strategy.canHandle(tempDir, config))
    }

    @Test
    fun `initialize creates workspace manager`() = runBlocking {
        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))

        val job = strategy.initialize(tempDir, config)

        // Job is returned for async plugin loading
        assertNotNull(job)

        // After initialization, workspace manager should be available
        // (tested via capability methods)
        assertNotNull(strategy.getAllMetadata())
    }

    @Test
    fun `isJenkinsFile returns false before initialization`() {
        val uri = tempDir.resolve("Jenkinsfile").toUri()

        assertFalse(strategy.isJenkinsFile(uri))
    }

    @Test
    fun `isJenkinsFile delegates to workspace manager after init`() = runBlocking {
        // Create a Jenkinsfile
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        Files.writeString(jenkinsfile, "pipeline { agent any }")

        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        strategy.initialize(tempDir, config)

        assertTrue(strategy.isJenkinsFile(jenkinsfile.toUri()))
        assertFalse(strategy.isJenkinsFile(tempDir.resolve("build.gradle").toUri()))
    }

    @Test
    fun `getGlobalVariables returns empty list before initialization`() {
        assertTrue(strategy.getGlobalVariables().isEmpty())
    }

    @Test
    fun `getGlobalVariables returns vars from workspace after init`() = runBlocking {
        // Create vars directory with a global variable
        val varsDir = tempDir.resolve("vars")
        Files.createDirectories(varsDir)
        Files.writeString(
            varsDir.resolve("myGlobal.groovy"),
            """
            def call() {
                echo "Hello from myGlobal"
            }
            """.trimIndent(),
        )

        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        strategy.initialize(tempDir, config)

        val globals = strategy.getGlobalVariables()
        assertTrue(globals.any { it.name == "myGlobal" })
    }

    @Test
    fun `getClasspathForFile returns null for non-Jenkins files`() = runBlocking {
        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        strategy.initialize(tempDir, config)

        val gradleFile = tempDir.resolve("build.gradle")
        Files.writeString(gradleFile, "apply plugin: 'java'")

        val classpath = strategy.getClasspathForFile(
            gradleFile.toUri(),
            "apply plugin: 'java'",
            emptyList(),
        )

        assertNull(classpath)
    }

    @Test
    fun `getClasspathForFile returns classpath for Jenkins files`() = runBlocking {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        Files.writeString(jenkinsfile, "pipeline { agent any }")

        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        strategy.initialize(tempDir, config)

        val projectDeps = listOf(tempDir.resolve("lib.jar"))
        val classpath = strategy.getClasspathForFile(
            jenkinsfile.toUri(),
            "pipeline { agent any }",
            projectDeps,
        )

        assertNotNull(classpath)
        // Should include project dependencies at minimum
        assertTrue(classpath!!.containsAll(projectDeps))
    }

    @Test
    fun `getSourceRoots returns empty before initialization`() {
        assertTrue(strategy.getSourceRoots().isEmpty())
    }

    @Test
    fun `shutdown clears internal state`() = runBlocking {
        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        strategy.initialize(tempDir, config)

        // Verify initialized
        assertNotNull(strategy.getAllMetadata())

        // Shutdown
        strategy.shutdown()

        // After shutdown, capability methods return defaults
        assertFalse(strategy.isJenkinsFile(URI("file:///any")))
        assertTrue(strategy.getGlobalVariables().isEmpty())
        assertNull(strategy.getAllMetadata())
    }

    @Test
    fun `updateConfiguration recreates workspace manager`() = runBlocking {
        val initialConfig = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        strategy.initialize(tempDir, initialConfig)

        // Update with new patterns
        val newConfig = createConfig(filePatterns = listOf("**/Jenkinsfile", "**/pipeline.groovy"))
        strategy.updateConfiguration(newConfig)

        // Strategy should still function
        assertTrue(strategy.canHandle(tempDir, newConfig))
    }

    @Test
    fun `awaitInitialization waits for async job`() = runBlocking {
        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        val job = strategy.initialize(tempDir, config)

        assertNotNull(job)

        // Should not throw and should complete
        strategy.awaitInitialization()

        // After awaiting, async init should be complete
        assertTrue(job!!.isCompleted)
    }

    @Test
    fun `isGdslFile detects gdsl files`() = runBlocking {
        val gdslFile = tempDir.resolve("jenkins.gdsl")
        Files.writeString(gdslFile, "// GDSL content")

        val config = createConfig(filePatterns = listOf("**/Jenkinsfile"))
        strategy.initialize(tempDir, config)

        assertTrue(strategy.isGdslFile(gdslFile.toUri()))
        assertFalse(strategy.isGdslFile(tempDir.resolve("other.groovy").toUri()))
    }

    private fun createConfig(filePatterns: List<String> = emptyList()): ServerConfiguration {
        val jenkinsConfig = JenkinsConfiguration(
            filePatterns = filePatterns,
        )
        return ServerConfiguration(jenkinsConfig = jenkinsConfig)
    }
}
