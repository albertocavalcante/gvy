package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroovyTextDocumentServiceSemanticTokensTest {

    private lateinit var service: GroovyTextDocumentService
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var tempWorkspace: java.nio.file.Path

    @BeforeEach
    fun setup() {
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val compilationService = GroovyCompilationService()

        tempWorkspace = Files.createTempDirectory("groovy-lsp-test")
        compilationService.workspaceManager.initializeWorkspace(tempWorkspace)

        // Initialize Jenkins workspace with default configuration
        compilationService.workspaceManager.initializeJenkinsWorkspace(
            com.github.albertocavalcante.groovylsp.config.ServerConfiguration(),
        )

        service = GroovyTextDocumentService(
            coroutineScope = coroutineScope,
            compilationService = compilationService,
            options = GroovyTextDocumentServiceOptions(
                client = { null },
            ),
        )
    }

    @AfterEach
    fun teardown() {
        tempWorkspace.toFile().deleteRecursively()
    }

    @Test
    fun `should return semantic tokens for Jenkins pipeline`(): Unit = runBlocking {
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

        val uri = "file://$tempWorkspace/Jenkinsfile"

        // Open document
        service.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem().apply {
                    this.uri = uri
                    languageId = "groovy"
                    version = 1
                    text = jenkinsfile
                }
            },
        )

        // Wait for compilation to complete
        service.awaitDiagnostics(java.net.URI.create(uri))

        // Request semantic tokens
        val result = service.semanticTokensFull(
            SemanticTokensParams(TextDocumentIdentifier(uri)),
        ).get()

        // Should have tokens
        assertTrue(result.data.isNotEmpty(), "Should return tokens for Jenkinsfile")

        // Data should be in groups of 5 integers
        assertEquals(0, result.data.size % 5, "Token data should be groups of 5 integers")

        // Should contain pipeline, agent, stages, stage, steps tokens
        val tokenCount = result.data.size / 5
        assertTrue(tokenCount >= 5, "Should have at least 5 tokens (pipeline, agent, stages, stage, steps)")
    }

    @Test
    fun `should return empty tokens for non-Jenkins file`(): Unit = runBlocking {
        val groovyFile = """
            class Example {
                def method() {
                    println "hello"
                }
            }
        """.trimIndent()

        val uri = "file://$tempWorkspace/Example.groovy"

        service.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem().apply {
                    this.uri = uri
                    languageId = "groovy"
                    version = 1
                    text = groovyFile
                }
            },
        )

        val result = service.semanticTokensFull(
            SemanticTokensParams(TextDocumentIdentifier(uri)),
        ).get()

        // Should be empty (no Jenkins blocks in regular Groovy file)
        assertTrue(result.data.isEmpty(), "Regular Groovy files should have no Jenkins tokens")
    }

    @Test
    fun `should handle Jenkins file with wrapper blocks`(): Unit = runBlocking {
        val jenkinsfile = """
            pipeline {
                agent any
                stages {
                    stage('Deploy') {
                        steps {
                            timeout(time: 10, unit: 'MINUTES') {
                                withCredentials([usernamePassword(credentialsId: 'creds')]) {
                                    sh 'deploy.sh'
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val uri = "file://$tempWorkspace/Jenkinsfile"

        service.didOpen(
            DidOpenTextDocumentParams().apply {
                textDocument = TextDocumentItem().apply {
                    this.uri = uri
                    languageId = "groovy"
                    version = 1
                    text = jenkinsfile
                }
            },
        )

        // Wait for compilation to complete
        service.awaitDiagnostics(java.net.URI.create(uri))

        val result = service.semanticTokensFull(
            SemanticTokensParams(TextDocumentIdentifier(uri)),
        ).get()

        // Should have tokens
        assertTrue(result.data.isNotEmpty(), "Should return tokens for Jenkinsfile with wrappers")

        // Should contain timeout, withCredentials, usernamePassword tokens
        val tokenCount = result.data.size / 5
        assertTrue(tokenCount >= 8, "Should have tokens for pipeline structure and wrappers")
    }
}
