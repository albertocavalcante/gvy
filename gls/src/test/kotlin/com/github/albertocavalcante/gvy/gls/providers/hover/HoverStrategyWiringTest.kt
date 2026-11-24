// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.documentation.DocumentationProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ClassDeclarationHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ClosureHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ConstructorCallHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.DeclarationExpressionHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.FieldDeclarationHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.GenericHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.ImportHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.LiteralHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.MethodCallHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.MethodDeclarationHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.PropertyExpressionHoverStrategy
import com.github.albertocavalcante.gvy.gls.providers.hover.strategies.VariableExpressionHoverStrategy
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pins how [HoverProvider] wires its strategies: built once, injectable, in a fixed order. */
class HoverStrategyWiringTest {
    private val compilationService = GroovyCompilationService()
    private val documentProvider = DocumentProvider()
    private val contentGenerator = HoverContentGenerator(
        SemanticTypeResolver(compilationService.classpathService.getTypeSolver()),
    )

    @BeforeEach
    fun resetDocumentationProvider() {
        DocumentationProvider.reset()
    }

    @Test
    fun `the strategy list is not rebuilt per request`() {
        val provider = HoverProvider(compilationService, documentProvider, contentGenerator)

        val first = provider.strategies
        val second = provider.strategies

        assertEquals(first.size, second.size)
        first.indices.forEach { index ->
            assertSame(
                first[index],
                second[index],
                "Strategy ${first[index]::class.simpleName} was rebuilt between requests",
            )
        }
    }

    @Test
    fun `the default strategies keep their specific-first order`() {
        val provider = HoverProvider(compilationService, documentProvider, contentGenerator)

        assertEquals(
            listOf(
                MethodDeclarationHoverStrategy::class,
                ClassDeclarationHoverStrategy::class,
                FieldDeclarationHoverStrategy::class,
                ImportHoverStrategy::class,
                DeclarationExpressionHoverStrategy::class,
                VariableExpressionHoverStrategy::class,
                MethodCallHoverStrategy::class,
                ConstructorCallHoverStrategy::class,
                PropertyExpressionHoverStrategy::class,
                ClosureHoverStrategy::class,
                LiteralHoverStrategy::class,
                GenericHoverStrategy::class,
            ),
            provider.strategies.map { it::class },
        )
    }

    @Test
    fun `an injected list is the one that answers requests`(): Unit = runTest {
        val recording = RecordingStrategy()
        val provider = HoverProvider(
            compilationService,
            documentProvider,
            contentGenerator,
            baseStrategies = listOf(recording),
        )
        val uri = URI.create("file:///injected-strategies.groovy")
        val source = "def greeting = 'hi'"
        documentProvider.put(uri, source)
        compilationService.compile(uri, source)

        val first = provider.provideHover(uri.toString(), Position(0, 5))
        val second = provider.provideHover(uri.toString(), Position(0, 5))

        assertNotNull(first)
        assertNotNull(second)
        assertEquals("injected", first.contents.right.value)
        assertTrue(recording.handled >= 2, "Injected strategy should serve both requests, served ${recording.handled}")
    }

    private class RecordingStrategy : HoverStrategy {
        var handled = 0
            private set

        override fun canHandle(node: ASTNode): Boolean = true

        override fun generateHover(node: ASTNode, context: HoverContext): Hover {
            handled++
            return Hover().apply {
                contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "injected"))
            }
        }
    }
}
