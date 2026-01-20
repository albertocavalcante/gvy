package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeclarativeBlockLevelCompletionTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var strategyRegistry: ProjectStrategyRegistry

    @BeforeEach
    fun setUp() = runBlocking {
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(tempDir)

        val jenkinsStrategy = JenkinsProjectStrategy(coroutineScope)
        strategyRegistry = ProjectStrategyRegistry()
        strategyRegistry.register(jenkinsStrategy)
        compilationService.workspaceManager.setStrategyRegistry(strategyRegistry)

        val config = ServerConfiguration(
            jenkinsConfig = com.github.albertocavalcante.groovyjenkins.JenkinsConfiguration(
                filePatterns = listOf("**/Jenkinsfile", "**/Jenkinsfile.*"),
            ),
        )
        strategyRegistry.selectStrategies(tempDir, config)
        jenkinsStrategy.initialize(tempDir, config)
        jenkinsStrategy.awaitInitialization()
    }

    @AfterEach
    fun tearDown() {
        strategyRegistry.shutdown()
        coroutineScope.cancel()
    }

    @Test
    fun `declarative options should NOT appear inside method call parameters`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                options {
                    disableConcurrentBuilds(abortPrevious: true, )
                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        // Position cursor inside disableConcurrentBuilds() args - after the comma
        val completions = CompletionProvider.getContextualCompletions(
            uri = jenkinsfile.toUri().toString(),
            line = 2,
            character = 53, // After "true, "
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = code,
        )

        val labels = completions.map { it.label }
        assertFalse(labels.contains("buildDiscarder"), "buildDiscarder should NOT appear inside method call")
        assertFalse(labels.contains("timestamps"), "timestamps should NOT appear inside method call")
        assertFalse(labels.contains("disableResume"), "disableResume should NOT appear inside method call")
    }

    @Test
    fun `declarative options SHOULD appear at block level in options block`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                options {

                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        // Position cursor at empty line inside options block
        val completions = CompletionProvider.getContextualCompletions(
            uri = jenkinsfile.toUri().toString(),
            line = 2,
            character = 8,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = code,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("disableConcurrentBuilds"), "disableConcurrentBuilds SHOULD appear at block level")
        assertTrue(labels.contains("buildDiscarder"), "buildDiscarder SHOULD appear at block level")
    }
}
