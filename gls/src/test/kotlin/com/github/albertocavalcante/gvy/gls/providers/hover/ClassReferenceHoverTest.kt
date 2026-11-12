// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.documentation.DocumentationProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.test.runTest
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassReferenceHoverTest {
    @Test
    fun `class expression uses the declaring buffer documentation`(): Unit = runTest {
        classHover("    Object run() { Money.of(1) }") { content ->
            assertContains(content, "class Money")
            assertContains(content, "demo.money.Money")
            assertContains(content, "An immutable amount of money.")
            assertFalse(content.contains("Caller documentation"), content)
            assertFalse(content.contains("ClassExpression@"), content)
        }
    }

    @Test
    fun `import hover includes documentation from the imported class`(): Unit = runTest {
        classHover("    Object run() { Money.of(1) }", importPosition = true) { content ->
            assertContains(content, "import demo.money.Money")
            assertContains(content, "An immutable amount of money.")
        }
    }

    @Test
    fun `type usage includes documentation from the declaring class`(): Unit = runTest {
        classHover("    Money value") { content ->
            assertContains(content, "class Money")
            assertContains(content, "An immutable amount of money.")
            assertFalse(content.contains("Caller documentation"), content)
        }
    }

    private suspend fun classHover(use: String, importPosition: Boolean = false, checkContent: (String) -> Unit) {
        DocumentationProvider.reset()
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        try {
            val classUri = URI.create("file:///hover/demo/money/Money.groovy")
            val useUri = URI.create("file:///hover/demo/Use.groovy")
            val classSource = """
                package demo.money

                /** An immutable amount of money. */
                class Money {
                    static Money of(Number amount) { new Money() }
                }
            """.trimIndent()
            val useSource = """
                package demo

                import demo.money.Money

                /** Caller documentation must not leak. */
                class Use {
                $use
                }
            """.trimIndent()
            documents.put(classUri, classSource)
            documents.put(useUri, useSource)
            assertTrue(compilation.compile(useUri, useSource).isSuccess)
            val resolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver())
            val provider = HoverProvider(compilation, documents, HoverContentGenerator(resolver))
            val line = if (importPosition) 2 else useSource.lines().indexOfFirst { it.contains(use.trim()) }
            val character = useSource.lines()[line].indexOf("Money")

            val hover = assertNotNull(provider.provideHover(useUri.toString(), Position(line, character)))

            checkContent(hover.contents.right.value)
        } finally {
            compilation.shutdown()
            DocumentationProvider.reset()
        }
    }
}
