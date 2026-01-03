package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
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

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(tempDir)
        compilationService.workspaceManager.initializeJenkinsWorkspace(ServerConfiguration())
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
            content = content,
        )

        val labels = completions.map { it.label }
        assertTrue(labels.contains("sh"), "Steps block should still offer step completions")
    }
}
