package com.github.albertocavalcante.groovylsp.documentation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentationServiceTest {

    // Simple mock model - just returns null for everything
    private val mockModel = object : GroovyAstModel {
        override fun getParent(node: ASTNode) = null
        override fun getUri(node: ASTNode) = null
        override fun getNodes(uri: URI) = emptyList<ASTNode>()
        override fun getAllNodes() = emptyList<ASTNode>()
        override fun getAllClassNodes() = emptyList<ClassNode>()
        override fun getNodeAt(uri: URI, position: com.github.albertocavalcante.groovyparser.ast.types.Position) = null
        override fun getNodeAt(uri: URI, line: Int, character: Int) = null
        override fun contains(ancestor: ASTNode, descendant: ASTNode) = false
    }

    // Simple test node - use ArgumentListExpression instead of null
    private val testNode = MethodCallExpression(
        VariableExpression("this"),
        "testMethod",
        ArgumentListExpression(),
    )
    private val testUri = URI.create("file:///test.groovy")

    @Test
    fun `service returns doc from highest priority provider`() {
        val lowPriorityProvider = object : PluggableDocProvider {
            override val name = "LowPriority"
            override val priority = 1
            override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI) =
                GroovyDocumentation.markdown("Low priority doc")
        }

        val highPriorityProvider = object : PluggableDocProvider {
            override val name = "HighPriority"
            override val priority = 100
            override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI) =
                GroovyDocumentation.markdown("High priority doc")
        }

        val service = DocumentationService(listOf(lowPriorityProvider, highPriorityProvider))

        val result = service.getDocumentation(testNode, mockModel, testUri)

        assertNotNull(result)
        assertEquals("High priority doc", result.content)
    }

    @Test
    fun `service skips providers that return null`() {
        val nullProvider = object : PluggableDocProvider {
            override val name = "NullProvider"
            override val priority = 100
            override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI) = null
        }

        val fallbackProvider = object : PluggableDocProvider {
            override val name = "Fallback"
            override val priority = 1
            override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI) =
                GroovyDocumentation.markdown("Fallback doc")
        }

        val service = DocumentationService(listOf(nullProvider, fallbackProvider))

        val result = service.getDocumentation(testNode, mockModel, testUri)

        assertNotNull(result)
        assertEquals("Fallback doc", result.content)
    }

    @Test
    fun `service respects canHandle filter`() {
        val selectiveProvider = object : PluggableDocProvider {
            override val name = "Selective"
            override val priority = 100
            override fun canHandle(node: ASTNode, documentUri: URI) = documentUri.path.endsWith(".jenkins")
            override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI) =
                GroovyDocumentation.markdown("Jenkins only")
        }

        val fallbackProvider = object : PluggableDocProvider {
            override val name = "Fallback"
            override val priority = 1
            override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI) =
                GroovyDocumentation.markdown("Fallback")
        }

        val service = DocumentationService(listOf(selectiveProvider, fallbackProvider))

        // Should skip selective provider for .groovy file
        val groovyResult = service.getDocumentation(testNode, mockModel, URI.create("file:///test.groovy"))
        assertEquals("Fallback", groovyResult?.content)

        // Should use selective provider for .jenkins file
        val jenkinsResult = service.getDocumentation(testNode, mockModel, URI.create("file:///test.jenkins"))
        assertEquals("Jenkins only", jenkinsResult?.content)
    }

    @Test
    fun `service returns null when no providers handle node`() {
        val service = DocumentationService(emptyList())

        val result = service.getDocumentation(testNode, mockModel, testUri)

        assertNull(result)
    }

    @Test
    fun `builder creates service with providers`() {
        val provider = object : PluggableDocProvider {
            override val name = "Test"
            override fun generateDoc(node: ASTNode, model: GroovyAstModel, documentUri: URI) =
                GroovyDocumentation.markdown("Test doc")
        }

        val service = DocumentationService.builder()
            .addProvider(provider)
            .build()

        val result = service.getDocumentation(testNode, mockModel, testUri)
        assertNotNull(result)
    }
}

class GroovyDocumentationTest {

    @Test
    fun `documentation can be combined`() {
        val doc1 = GroovyDocumentation.markdown("Part 1", source = "Source A")
        val doc2 = GroovyDocumentation.markdown("Part 2", source = "Source B")

        val combined = doc1 + doc2

        assertTrue(combined.content.contains("Part 1"))
        assertTrue(combined.content.contains("Part 2"))
        assertEquals("Source A, Source B", combined.source)
    }

    @Test
    fun `markdown factory creates correct format`() {
        val doc = GroovyDocumentation.markdown("# Header", source = "Test")

        assertEquals("# Header", doc.content)
        assertEquals(GroovyDocumentation.Format.MARKDOWN, doc.format)
        assertEquals("Test", doc.source)
    }

    @Test
    fun `plaintext factory creates correct format`() {
        val doc = GroovyDocumentation.plaintext("Plain text")

        assertEquals("Plain text", doc.content)
        assertEquals(GroovyDocumentation.Format.PLAINTEXT, doc.format)
    }
}
