// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.typedefinition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeDefinitionProviderTest {
    private val testUri = URI.create("file:///test.groovy")

    @Test
    fun `typeDefinitions returns empty list when no compilation context`(): Unit = runBlocking {
        val provider = TypeDefinitionProvider(
            semanticResolver = mockk<SemanticTypeResolver>(relaxed = true),
            contextProvider = { _: URI -> null },
        )
        val params = TypeDefinitionParams(TextDocumentIdentifier(testUri.toString()), Position(5, 10))

        assertTrue(provider.typeDefinitions(params).isEmpty())
    }

    @Test
    fun `typeDefinitions resolves after a supplementary character on the line`(): Unit = runBlocking {
        val compilationService = GroovyCompilationService()
        val uri = URI.create("file:///TypeEmoji.groovy")
        val source = "class Foo {}\ndef s = '\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00'; Foo f = new Foo(); println f"
        assertTrue(compilationService.compile(uri, source).isSuccess)
        val provider = TypeDefinitionProvider(
            semanticResolver = SemanticTypeResolver(compilationService.classpathService.getTypeSolver()),
            contextProvider = { compilationService.createContext(it) },
            sourceProvider = { compilationService.getCompiledSnapshot(it)?.sourceText },
        )
        val position = Position(1, source.lines()[1].lastIndexOf('f'))
        val params = TypeDefinitionParams(TextDocumentIdentifier(uri.toString()), position)

        assertEquals(listOf(0), provider.typeDefinitions(params).map { it.range.start.line })
    }
}
