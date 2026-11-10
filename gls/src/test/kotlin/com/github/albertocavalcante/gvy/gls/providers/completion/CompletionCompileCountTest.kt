package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/** Each transient compile is a full parse, so a keystroke should pay for one when one suffices (audit H11). */
class CompletionCompileCountTest {
    private val uri = URI.create("file:///CompileCount.groovy")
    private val compilation = spyk(GroovyCompilationService())
    private val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())

    @AfterEach
    fun close() = compilation.clearCaches()

    @Test
    fun `an unrelated error elsewhere costs one compile when the dummy parsed as an expression`(): Unit = runBlocking {
        // `Missing` cannot resolve, so the buffer never compiles cleanly, but `value.` parses fine.
        val source = "Missing broken = null\nString value = 'text'\nvalue."

        val items = complete(source)

        assertTrue(items.any { it.label == "substring" && it.kind == CompletionItemKind.Method })
        coVerify(exactly = 1) { compilation.compileTransient(uri, any(), any()) }
    }

    @Test
    fun `a bare class body still retries with def`(): Unit = runBlocking {
        val source = "class Greeter {\n    \n}"

        val items = complete(source, line = 1, character = 4)

        assertTrue(items.isNotEmpty(), "class-body completion keeps working")
        coVerify(atLeast = 2) { compilation.compileTransient(uri, any(), any()) }
    }

    private suspend fun complete(
        source: String,
        line: Int = source.lines().lastIndex,
        character: Int = source.lines().last().length,
    ): List<CompletionItem> = CompletionProvider.getContextualCompletions(
        uri = uri.toString(),
        line = line,
        character = character,
        compilationService = compilation,
        semanticResolver = resolver,
        content = source,
    )
}
