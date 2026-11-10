package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItemKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** The placeholder inserted into the parsing copy must never appear as a suggestion. */
class DummyIdentifierLeakTest {
    private val compilation = GroovyCompilationService()
    private val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())

    @AfterEach
    fun close() = compilation.clearCaches()

    @Test
    fun `class body completion does not offer the inserted placeholder`(): Unit = runBlocking {
        val source = "class Other {}\nclass Greeter {\n    \n}"

        val items = CompletionProvider.getContextualCompletions(
            uri = "file:///Leak.groovy",
            line = 2,
            character = 4,
            compilationService = compilation,
            semanticResolver = resolver,
            content = source,
        )

        val labels = items.map { it.label }
        assertTrue(items.any { it.label == "Other" && it.kind == CompletionItemKind.Class }, "AST path: $labels")
        assertTrue(labels.none { it.contains(CompletionProvider.DUMMY_IDENTIFIER) }, "found $labels")
    }
}
