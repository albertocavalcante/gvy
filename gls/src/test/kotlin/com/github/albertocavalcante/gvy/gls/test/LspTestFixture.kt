package com.github.albertocavalcante.gvy.gls.test

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContentGenerator
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URI

/**
 * Reusable fixture for testing LSP features against compiled Groovy code.
 */
class LspTestFixture {
    val compilationService = GroovyCompilationService()
    val uri = URI.create("file:///test.groovy")

    // Real document provider
    val documentProvider = DocumentProvider()

    fun compile(content: String) = runBlocking {
        documentProvider.put(uri, content)
        val result = compilationService.compile(uri, content)
        if (!result.isSuccess) {
            throw AssertionError("Compilation failed: ${result.diagnostics}")
        }
    }

    fun assertCompletionContains(line: Int, char: Int, vararg expectedLabels: String) {
        val content = documentProvider.get(uri) ?: ""
        val completions = runBlocking {
            CompletionProvider.getContextualCompletions(
                uri.toString(),
                line,
                char,
                compilationService,
                SemanticTypeResolver(ReflectionTypeSolver()),
                content,
            )
        }

        val labels = completions.map { it.label }.toSet()
        val missing = expectedLabels.filter { it !in labels }

        assertTrue(missing.isEmpty(), "Missing completions at $line:$char. Expected $missing to be in $labels")
    }

    fun assertCompletionDoesNotContain(line: Int, char: Int, vararg unexpectedLabels: String) {
        val content = documentProvider.get(uri) ?: ""
        val completions = runBlocking {
            CompletionProvider.getContextualCompletions(
                uri.toString(),
                line,
                char,
                compilationService,
                SemanticTypeResolver(ReflectionTypeSolver()),
                content,
            )
        }

        val labels = completions.map { it.label }.toSet()
        val present = unexpectedLabels.filter { it in labels }

        assertTrue(present.isEmpty(), "Unexpected completions at $line:$char. Found $present in $labels")
    }

    fun assertHoverContains(line: Int, char: Int, expectedText: String) = runBlocking {
        val resolver = mockk<SemanticTypeResolver>(relaxed = true)
        val generator = HoverContentGenerator(resolver)
        val hoverProvider = HoverProvider(compilationService, documentProvider, generator)
        val hover = hoverProvider.provideHover(uri.toString(), Position(line, char))

        assertTrue(hover != null, "Hover should not be null at $line:$char")

        val content = if (hover!!.contents.isLeft) {
            // Handle LSP4J string unions safely.
            val marked = hover.contents.left.first()
            if (marked.isLeft) {
                marked.left
            } else {
                @Suppress("DEPRECATION")
                val legacy = marked.right
                legacy.value
            }
        } else {
            hover.contents.right.value
        }

        assertTrue(
            content.contains(expectedText),
            "Hover at $line:$char should contain '$expectedText'. Actual:\n$content",
        )
    }
}
