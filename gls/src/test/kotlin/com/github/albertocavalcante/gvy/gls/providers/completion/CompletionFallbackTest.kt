package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.test.LspTestFixture
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CompletionFallbackTest {

    private lateinit var fixture: LspTestFixture

    @BeforeEach
    fun setUp() {
        fixture = LspTestFixture()
    }

    @Test
    fun `fallback keywords appear when compilation fails`() {
        val code = """
            def x =
            println x
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val completions = runBlocking {
            CompletionProvider.getContextualCompletions(
                fixture.uri.toString(),
                1,
                0,
                fixture.compilationService,
                SemanticTypeResolver(ReflectionTypeSolver()),
                code,
            )
        }

        val labels = completions.map { it.label }
        assertTrue(labels.contains("def"))
        assertTrue(labels.contains("class"))
    }
}
