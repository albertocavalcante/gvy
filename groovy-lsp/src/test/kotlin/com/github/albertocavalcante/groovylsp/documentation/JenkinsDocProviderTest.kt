package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyjenkins.JenkinsContext
import com.github.albertocavalcante.groovyjenkins.JenkinsPluginManager
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.StepParameter
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JenkinsDocProviderTest {
    private val pluginManager = mockk<JenkinsPluginManager>()
    private val context = mockk<JenkinsContext>()
    private val provider = JenkinsDocProvider(pluginManager, context)
    private val uri = URI.create("file:///Jenkinsfile")
    private val model = mockk<GroovyAstModel>()

    @Test
    fun `should handle Jenkinsfiles`() {
        every { context.isJenkinsFile(uri) } returns true
        val node = mockk<ASTNode>()
        assertTrue(provider.canHandle(node, uri))
    }

    @Test
    fun `should handle files with Jenkinsfile extension`() {
        val jenkinsUri = URI.create("file:///Project.Jenkinsfile")
        val node = mockk<ASTNode>()
        val localProvider = JenkinsDocProvider(pluginManager, null)
        assertTrue(localProvider.canHandle(node, jenkinsUri))
    }

    @Test
    fun `should provide documentation for resolved step`() {
        val node = mockk<MethodCallExpression>()
        every { node.methodAsString } returns "sh"

        val metadata = JenkinsStepMetadata(
            name = "sh",
            plugin = "workflow-durable-task-step",
            parameters = mapOf(
                "script" to StepParameter("script", "String", required = true, documentation = "The script to run"),
            ),
            documentation = "Run a shell script",
        )

        coEvery { pluginManager.resolveStepMetadata("sh") } returns metadata

        val doc = provider.generateDoc(node, model, uri)

        assertNotNull(doc)
        assertTrue(doc.content.contains("sh"))
        assertTrue(doc.content.contains("Run a shell script"))
        assertTrue(doc.content.contains("workflow-durable-task-step"))
        assertTrue(doc.content.contains("script"))
    }

    @Test
    fun `should provide fallback documentation for known step`() {
        val node = mockk<MethodCallExpression>()
        every { node.methodAsString } returns "git"

        coEvery { pluginManager.resolveStepMetadata("git") } returns null

        val doc = provider.generateDoc(node, model, uri)

        assertNotNull(doc)
        assertTrue(doc.content.contains("git"))
        assertTrue(doc.content.contains("Jenkins Pipeline step"))
    }

    @Test
    fun `should return null for unknown step`() {
        val node = mockk<MethodCallExpression>()
        every { node.methodAsString } returns "unknownStep"

        coEvery { pluginManager.resolveStepMetadata("unknownStep") } returns null

        val doc = provider.generateDoc(node, model, uri)

        assertNull(doc)
    }

    @Test
    fun `should provide quick navigate info`() {
        val node = mockk<MethodCallExpression>()
        every { node.methodAsString } returns "sh"

        val info = provider.getQuickNavigateInfo(node)
        assertEquals("Jenkins step: sh", info)
    }
}
