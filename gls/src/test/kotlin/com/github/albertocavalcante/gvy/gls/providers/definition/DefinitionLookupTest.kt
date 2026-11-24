// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.errors.InvalidPositionException
import com.github.albertocavalcante.gvy.gls.errors.NodeNotFoundAtPositionException
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The resolver's boundary says what happened: found, not found, or failed with the failure's type. */
class DefinitionLookupTest {
    private val uri = URI.create("file:///Lookup.groovy")
    private val compilation = spyk(GroovyCompilationService())

    private fun resolver(text: String): DefinitionResolver = runBlocking {
        compilation.compile(uri, text)
        val snapshot = checkNotNull(compilation.getCompiledSnapshot(uri))
        DefinitionResolver(
            snapshot.parseResult.astModel,
            snapshot.parseResult.symbolTable,
            compilation,
            null,
            compilation.getWorkspaceSymbolIndex(),
        )
    }

    @Test
    fun `a resolvable symbol is found`(): Unit = runBlocking {
        val lookup = resolver("def value = 1\nprintln value\n").findDefinitionAt(uri, Position(1, 9))
        assertIs<DefinitionResolver.DefinitionLookup.Found>(lookup)
    }

    @Test
    fun `an unresolvable class is not found`(): Unit = runBlocking {
        val position = Position(5, 10)
        val model = mockk<GroovyAstModel>()
        val missing = ClassNode("com.example.Missing", 0, null).apply {
            lineNumber = 5
            columnNumber = 10
            lastLineNumber = 5
            lastColumnNumber = 20
        }
        every { model.getNodeAt(uri, position) } returns missing
        every { model.getAllClassNodes() } returns emptyList()
        val service = mockk<GroovyCompilationService>(relaxed = true)
        every { service.getAst(uri) } returns null
        every { service.getAllSymbolStorages() } returns emptyMap()
        every { service.findClasspathClass(any()) } returns null

        val lookup = DefinitionResolver(model, mockk<SymbolTable>(), service).findDefinitionAt(uri, position)

        assertIs<DefinitionResolver.DefinitionLookup.NotFound>(lookup)
    }

    @Test
    fun `negative coordinates fail as an invalid position`(): Unit = runBlocking {
        val lookup = resolver("def value = 1\n").findDefinitionAt(uri, Position(-1, 0))
        val failed = assertIs<DefinitionResolver.DefinitionLookup.Failed>(lookup)
        assertIs<InvalidPositionException>(failed.error)
    }

    @Test
    fun `a string literal fails as no node at the position`(): Unit = runBlocking {
        val lookup = resolver("println \"literal\"\n").findDefinitionAt(uri, Position(0, 10))
        val failed = assertIs<DefinitionResolver.DefinitionLookup.Failed>(lookup)
        assertIs<NodeNotFoundAtPositionException>(failed.error)
    }

    @Test
    fun `an unexpected failure propagates instead of becoming an unknown symbol`(): Unit = runBlocking {
        val resolver = resolver("def value = 1\nprintln value\n")
        every { compilation.getAst(uri) } throws IllegalStateException("ast unavailable")

        val failure = assertFailsWith<IllegalStateException> { resolver.findDefinitionAt(uri, Position(1, 9)) }
        assertEquals("ast unavailable", failure.message)
    }
}
