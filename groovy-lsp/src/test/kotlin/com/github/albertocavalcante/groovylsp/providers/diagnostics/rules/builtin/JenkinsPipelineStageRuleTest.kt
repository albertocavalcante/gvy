package com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.builtin

import com.github.albertocavalcante.groovylsp.providers.diagnostics.rules.RuleContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JenkinsPipelineStageRuleTest {

    private val rule = JenkinsPipelineStageRule()

    @Test
    fun `should detect incomplete Jenkins stage in Jenkinsfile`() = runBlocking {
        val code = """
            pipeline {
                agent any
                stages {
                    stage('Build') {
                        // Missing steps block
                    }
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///Jenkinsfile"), code, context)

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics.first()
        assertTrue(diagnostic.message.contains("steps"))
    }

    @Test
    fun `should not flag complete Jenkins stage`() = runBlocking {
        val code = """
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

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///Jenkinsfile"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should not flag stage with script block`() = runBlocking {
        val code = """
            stage('Deploy') {
                script {
                    echo 'Deploying...'
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///Jenkinsfile"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should not analyze non-Jenkins files`() = runBlocking {
        val code = """
            stage('Build') {
                // This is not a Jenkins file
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///NotAJenkinsfile.groovy"), code, context)

        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should analyze files in vars directory`() = runBlocking {
        val code = """
            def call() {
                stage('Test') {
                    // Missing steps
                }
            }
        """.trimIndent()

        val context = mockContext()
        val diagnostics = rule.analyze(URI.create("file:///vars/myPipeline.groovy"), code, context)

        assertEquals(1, diagnostics.size)
    }

    private fun mockContext(): RuleContext {
        val context = mockk<RuleContext>()
        every { context.hasErrors() } returns false
        every { context.getAst() } returns null
        return context
    }
}
