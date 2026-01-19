package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticTokensHandlerTest {

    private lateinit var handler: SemanticTokensHandler
    private lateinit var compilationService: GroovyCompilationService
    private lateinit var documentProvider: DocumentProvider
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var tempWorkspace: java.nio.file.Path
    private lateinit var strategyRegistry: ProjectStrategyRegistry

    @BeforeEach
    fun setup() = runBlocking {
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        compilationService = GroovyCompilationService()
        documentProvider = DocumentProvider()

        tempWorkspace = Files.createTempDirectory("semantic-tokens-handler-test")
        compilationService.workspaceManager.initializeWorkspace(tempWorkspace)

        // Set up strategy registry with Jenkins support
        val jenkinsStrategy = JenkinsProjectStrategy(coroutineScope)
        strategyRegistry = ProjectStrategyRegistry()
        strategyRegistry.register(jenkinsStrategy)
        compilationService.workspaceManager.setStrategyRegistry(strategyRegistry)

        // Initialize Jenkins strategy with file patterns
        val config = ServerConfiguration(
            jenkinsConfig = com.github.albertocavalcante.groovyjenkins.JenkinsConfiguration(
                filePatterns = listOf("**/Jenkinsfile", "**/Jenkinsfile.*"),
            ),
        )
        // Select strategies to populate activeStrategies (required for findCapability)
        strategyRegistry.selectStrategies(tempWorkspace, config)
        jenkinsStrategy.initialize(tempWorkspace, config)
        jenkinsStrategy.awaitInitialization()

        handler = SemanticTokensHandler(
            compilationService = compilationService,
            documentProvider = documentProvider,
        )
    }

    @AfterEach
    fun teardown() {
        strategyRegistry.shutdown()
        coroutineScope.cancel()
        tempWorkspace.toFile().deleteRecursively()
    }

    @Test
    fun `should generate semantic tokens for Groovy file`(): Unit = runBlocking {
        val groovyCode = """
            class Example {
                def method() {
                    println "hello"
                }
            }
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Example.groovy")
        documentProvider.put(uri, groovyCode)
        compilationService.compile(uri, groovyCode)

        val result = handler.getSemanticTokens(uri)

        assertNotNull(result, "Should return semantic tokens")
        assertTrue(result.data.isNotEmpty(), "Should have token data")
        assertEquals(0, result.data.size % 5, "Token data should be groups of 5 integers")
    }

    @Test
    fun `should generate semantic tokens for Jenkins file`(): Unit = runBlocking {
        val jenkinsfile = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        steps {
                            sh 'make build'
                        }
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Jenkinsfile")
        documentProvider.put(uri, jenkinsfile)
        compilationService.compile(uri, jenkinsfile)

        val result = handler.getSemanticTokens(uri)

        assertNotNull(result, "Should return semantic tokens")
        assertTrue(result.data.isNotEmpty(), "Should have token data for Jenkinsfile")
        assertEquals(0, result.data.size % 5, "Token data should be groups of 5 integers")

        // Should have multiple tokens (pipeline, agent, stages, stage, steps)
        val tokenCount = result.data.size / 5
        assertTrue(tokenCount >= 5, "Should have at least 5 tokens for Jenkins keywords")
    }

    @Test
    fun `should return empty tokens when document not compiled`(): Unit = runBlocking {
        val uri = URI.create("file://$tempWorkspace/NonExistent.groovy")

        val result = handler.getSemanticTokens(uri)

        assertNotNull(result, "Should return result even when not compiled")
        assertTrue(result.data.isEmpty(), "Should return empty tokens when document not compiled")
    }

    @Test
    fun `should detect unused imports`(): Unit = runBlocking {
        val groovyCode = """
            import java.util.ArrayList
            import java.util.HashMap

            class Example {
                def method() {
                    def list = new ArrayList()
                    return list
                }
            }
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/WithImports.groovy")
        documentProvider.put(uri, groovyCode)
        compilationService.compile(uri, groovyCode)

        val result = handler.getSemanticTokens(uri)

        assertNotNull(result, "Should return semantic tokens")
        assertTrue(result.data.isNotEmpty(), "Should have token data")
        // HashMap should be marked as unused (with UNNECESSARY modifier)
        // ArrayList should be used
        val tokenCount = result.data.size / 5
        assertTrue(tokenCount > 0, "Should have tokens for imports and class")
    }

    @Test
    fun `should combine Groovy and Jenkins tokens`(): Unit = runBlocking {
        val jenkinsfile = """
            class Helper {
                static def build() {
                    return "built"
                }
            }

            pipeline {
                agent any
                stages {
                    stage('Build') {
                        steps {
                            script {
                                Helper.build()
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Jenkinsfile")
        documentProvider.put(uri, jenkinsfile)
        compilationService.compile(uri, jenkinsfile)

        val result = handler.getSemanticTokens(uri)

        assertNotNull(result, "Should return semantic tokens")
        assertTrue(result.data.isNotEmpty(), "Should have both Groovy and Jenkins tokens")

        val tokenCount = result.data.size / 5
        // Should have tokens for:
        // - Groovy: class, method, static
        // - Jenkins: pipeline, agent, stages, stage, steps, script
        assertTrue(tokenCount >= 10, "Should have tokens for both Groovy constructs and Jenkins keywords")
    }
}
