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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JenkinsDeclarativeCompletionTest {
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

        // Set up strategy registry with Jenkins support
        val jenkinsStrategy = JenkinsProjectStrategy(coroutineScope)
        strategyRegistry = ProjectStrategyRegistry()
        strategyRegistry.register(jenkinsStrategy)
        compilationService.workspaceManager.setStrategyRegistry(strategyRegistry)

        // Initialize Jenkins strategy with file patterns
        val config = ServerConfiguration(
            jenkinsConfig = com.github.albertocavalcante.gvy.jenkins.JenkinsConfiguration(
                filePatterns = listOf("**/Jenkinsfile", "**/Jenkinsfile.*"),
            ),
        )
        // Select strategies to populate activeStrategies (required for findCapability)
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
    fun `agent block should suggest agent types but not steps`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                agent {
                    
                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)
        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 2,
            character = 12,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("docker"), "Agent block should suggest agent types")
        assertFalse(labels.contains("sh"), "Agent block should not suggest step names")
    }

    @Test
    fun `options block should suggest declarative options`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                options {
                    
                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)
        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 2,
            character = 12,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("disableConcurrentBuilds"), "Options block should offer declarative options")
        assertFalse(labels.contains("sh"), "Options block should not suggest pipeline steps")
    }

    @Test
    fun `steps block should still suggest sh`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                stages {
                    stage('Build') {
                        steps {
                            
                        }
                    }
                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)
        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 4,
            character = 16,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("sh"), "Steps block should still offer step completions")
    }

    @Test
    fun `post block should suggest post conditions`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        steps {
                            sh 'echo hello'
                        }
                    }
                }
                post {
                    
                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)
        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 10,
            character = 8,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("success"), "Post block should suggest 'success'")
        assertTrue(labels.contains("failure"), "Post block should suggest 'failure'")
        assertTrue(labels.contains("always"), "Post block should suggest 'always'")
        assertTrue(labels.contains("notBuilt"), "Post block should suggest 'notBuilt'")
        assertFalse(labels.contains("sh"), "Post block should not suggest pipeline steps")
    }

    @Test
    @Disabled("TODO: Fix brittle completion logic (see issue)")
    fun `script block should suggest standard Groovy completions`() = runTest {
        val jenkinsfile = tempDir.resolve("Jenkinsfile")
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        steps {
                            script {
                                
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        Files.writeString(jenkinsfile, code)

        val uri = jenkinsfile.toUri().toString()
        val content = Files.readString(jenkinsfile)
        val completions = CompletionProvider.getContextualCompletions(
            uri = uri,
            line = 7,
            character = 20,
            compilationService = compilationService,
            semanticResolver = mockk(relaxed = true),
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("println"), "Script block should suggest standard Groovy methods like 'println'")
        assertTrue(labels.contains("sh"), "Script block should still suggest pipeline steps")
    }
}
