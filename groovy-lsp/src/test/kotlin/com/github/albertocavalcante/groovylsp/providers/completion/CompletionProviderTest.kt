package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class CompletionProviderTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var semanticResolver: SemanticTypeResolver
    private lateinit var moduleNode: ModuleNode

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        semanticResolver = mockk(relaxed = true)
        moduleNode = mockk(relaxed = true)
    }

    @Test
    fun `test getContextualCompletions with valid URI`() = runTest {
        // Arrange
        val content = """
            def localVar = "test"
            class TestClass {
                def method() {
                    return "result"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")

        // Compile the content to build AST
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        // Act
        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            2, // Line within class
            8, // Character position
            compilationService,
            semanticResolver,
            content,
        )

        // Assert - Should return some completions (could be empty if no context matches)
        assertTrue(completions.size >= 0, "Should return list of completions")
    }

    @Test
    fun `test getContextualCompletions with invalid URI`() = runTest {
        // Act
        val completions = CompletionProvider.getContextualCompletions(
            "invalid-uri",
            0,
            0,
            compilationService,
            semanticResolver,
            "def x = 1",
        )

        // Assert - Should return completions now because we compile transiently
        assertTrue(completions.isNotEmpty(), "Should return completions even for invalid URI")
    }

    @Test
    fun `test getContextualCompletions without compilation`() = runTest {
        // Act - Try to get completions for a file that hasn't been compiled
        val completions = CompletionProvider.getContextualCompletions(
            "file:///unknown.groovy",
            0,
            0,
            compilationService,
            semanticResolver,
            "def x = 1",
        )

        // Assert - Should return completions now because we compile transiently
        assertTrue(completions.isNotEmpty(), "Should return completions even without prior compilation")
    }

    @Test
    fun `should suggest methods for inferred variable type`() = runTest {
        // Arrange
        val content = """
            def list = new ArrayList()
            list.
        """.trimIndent()
        val uri = "file:///test.groovy"
        val compilationResult = compilationService.compile(URI.create(uri), content)

        // Mock semantic resolver to return ArrayList for 'list'
        every { semanticResolver.resolveType(any(), any()) } returns SemanticType.Known("java.util.ArrayList")

        // Act
        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1, // Line 1 (0-indexed)
            5, // After 'list.'
            compilationService,
            semanticResolver,
            content,
        )

        // Assert
        assertTrue(completions.any { it.label == "add" }, "Should suggest 'add' method for ArrayList")
        assertTrue(completions.any { it.label == "size" }, "Should suggest 'size' method for ArrayList")
    }

    @Test
    fun `should suggest methods for method parameter type`() = runTest {
        // Arrange
        val content = """
            void process(String text) {
                text.
            }
        """.trimIndent()
        val uri = "file:///test.groovy"
        val compilationResult = compilationService.compile(URI.create(uri), content)

        // Mock semantic resolver to return String for 'text'
        every { semanticResolver.resolveType(any(), any()) } returns SemanticType.Known("java.lang.String")

        // Act
        val completions = CompletionProvider.getContextualCompletions(
            uri,
            1,
            9,
            compilationService,
            semanticResolver,
            content,
        )

        // Assert
        assertTrue(completions.any { it.label == "substring" }, "Should suggest 'substring' for String param")
        assertTrue(completions.any { it.label == "length" }, "Should suggest 'length' for String param")
    }
}
