package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for vars/ global variable semantic token support.
 *
 * Tests that method calls matching known vars/ names get highlighted
 * with the FUNCTION token type for distinct visual treatment.
 */
class JenkinsSemanticTokenProviderVarsTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var tempWorkspace: java.nio.file.Path

    @BeforeEach
    fun setup() {
        compilationService = GroovyCompilationService()
        tempWorkspace = Files.createTempDirectory("groovy-lsp-vars-test")
        compilationService.workspaceManager.initializeWorkspace(tempWorkspace)
        compilationService.workspaceManager.initializeJenkinsWorkspace(
            com.github.albertocavalcante.groovylsp.config.ServerConfiguration(),
        )
    }

    @Test
    fun `should tokenize vars global variable calls`(): Unit = runBlocking {
        // Test standalone vars calls - not property access like infra.checkoutSCM()
        val code = """
            buildPluginWithGradle()
            buildDockerAndPublishImage('foo')
            buildPlugin()
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Jenkinsfile")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!
        val varsNames = setOf("buildPluginWithGradle", "buildDockerAndPublishImage", "buildPlugin")

        val tokens = JenkinsSemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            isJenkinsFile = true,
            varsNames = varsNames,
        )

        // Should have tokens for all three vars calls
        val varsTokens = tokens.filter { it.tokenType == JenkinsSemanticTokenProvider.TokenTypes.FUNCTION }
        assertEquals(3, varsTokens.size, "Should tokenize all 3 vars calls")

        // Verify first token is buildPluginWithGradle (on the first line of code)
        val firstToken = varsTokens.minByOrNull { it.line }!!
        assertEquals("buildPluginWithGradle".length, firstToken.length)
    }

    @Test
    fun `should not tokenize regular method calls as vars`(): Unit = runBlocking {
        val code = """
            println("hello")
            someOtherMethod()
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Jenkinsfile")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!
        val varsNames = setOf("buildPluginWithGradle", "infra")

        val tokens = JenkinsSemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            isJenkinsFile = true,
            varsNames = varsNames,
        )

        // None of these should be tokenized as vars (they're not in varsNames)
        val varsTokens = tokens.filter { it.tokenType == JenkinsSemanticTokenProvider.TokenTypes.FUNCTION }
        assertEquals(0, varsTokens.size, "Should not tokenize regular method calls as vars")
    }

    @Test
    fun `should handle nested vars calls inside pipeline blocks`(): Unit = runBlocking {
        val code = """
            pipeline {
                stages {
                    stage('Build') {
                        steps {
                            buildPluginWithGradle()
                        }
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Jenkinsfile")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!
        val varsNames = setOf("buildPluginWithGradle")

        val tokens = JenkinsSemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            isJenkinsFile = true,
            varsNames = varsNames,
        )

        // Should have MACRO tokens for pipeline, stages, stage, steps
        val macroTokens = tokens.filter { it.tokenType == JenkinsSemanticTokenProvider.TokenTypes.MACRO }
        assertTrue(macroTokens.size >= 4, "Should have MACRO tokens for pipeline structure")

        // Should have FUNCTION token for buildPluginWithGradle
        val functionTokens = tokens.filter { it.tokenType == JenkinsSemanticTokenProvider.TokenTypes.FUNCTION }
        assertEquals(1, functionTokens.size, "Should have one FUNCTION token for vars call")
        assertEquals("buildPluginWithGradle".length, functionTokens.first().length)
    }

    @Test
    fun `should handle empty varsNames set gracefully`(): Unit = runBlocking {
        val code = """
            buildPluginWithGradle()
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Jenkinsfile")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!

        val tokens = JenkinsSemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            isJenkinsFile = true,
            varsNames = emptySet(),
        )

        // With empty varsNames, no FUNCTION tokens should be generated for this call
        val functionTokens = tokens.filter { it.tokenType == JenkinsSemanticTokenProvider.TokenTypes.FUNCTION }
        assertEquals(0, functionTokens.size, "Empty varsNames should produce no vars tokens")
    }

    @Test
    fun `should distinguish vars from Jenkins built-in blocks`(): Unit = runBlocking {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Deploy') {
                        steps {
                            sh 'echo hello'
                            buildPluginWithGradle()
                        }
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/Jenkinsfile")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!
        val varsNames = setOf("buildPluginWithGradle")

        val tokens = JenkinsSemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            isJenkinsFile = true,
            varsNames = varsNames,
        )

        // pipeline, agent, stages, stage, steps should be MACRO
        val macroTokens = tokens.filter { it.tokenType == JenkinsSemanticTokenProvider.TokenTypes.MACRO }
        assertTrue(macroTokens.isNotEmpty(), "Should have MACRO tokens for Jenkins blocks")

        // buildPluginWithGradle should be FUNCTION
        val functionTokens = tokens.filter { it.tokenType == JenkinsSemanticTokenProvider.TokenTypes.FUNCTION }
        assertEquals(1, functionTokens.size, "Should have FUNCTION token for vars call")
    }

    @Test
    fun `should return empty list for non-Jenkins files even with varsNames`(): Unit = runBlocking {
        val code = """
            buildPluginWithGradle()
        """.trimIndent()

        val uri = URI.create("file://$tempWorkspace/test.groovy")
        compilationService.compile(uri, code)

        val astModel = compilationService.getAstModel(uri)!!
        val varsNames = setOf("buildPluginWithGradle")

        val tokens = JenkinsSemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            isJenkinsFile = false, // Not a Jenkins file
            varsNames = varsNames,
        )

        assertTrue(tokens.isEmpty(), "Non-Jenkins files should return empty tokens")
    }
}
