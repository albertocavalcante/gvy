package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.test.LspTestFixture
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
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
