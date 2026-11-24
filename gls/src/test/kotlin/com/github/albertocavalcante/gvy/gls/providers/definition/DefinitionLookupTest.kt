// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
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

    // An import of an unknown class reaches the classpath strategy, the last one tried.
    private val position = Position(0, 22)

    private fun missingClassResolver(classpath: (String) -> URI?): DefinitionResolver {
        runBlocking { compilation.compile(uri, "import com.example.Missing\n") }
        every { compilation.findClasspathClass(any()) } answers { classpath(firstArg()) }
        val snapshot = checkNotNull(compilation.getCompiledSnapshot(uri))
        return DefinitionResolver(
            snapshot.parseResult.astModel,
            snapshot.parseResult.symbolTable,
            compilation,
            null,
            compilation.getWorkspaceSymbolIndex(),
        )
    }

    @Test
    fun `an unresolvable class is not found`(): Unit = runBlocking {
        val lookup = missingClassResolver { null }.findDefinitionAt(uri, position)

        assertIs<DefinitionResolver.DefinitionLookup.NotFound>(lookup)
    }

    @Test
    fun `a strategy that throws fails the lookup with its own error`(): Unit = runBlocking {
        val lookup = missingClassResolver { throw IOException("jar unreadable") }.findDefinitionAt(uri, position)

        val failed = assertIs<DefinitionResolver.DefinitionLookup.Failed>(lookup)
        assertIs<IOException>(failed.error)
        assertEquals("jar unreadable", failed.error.message)
    }

    @Test
    fun `a cancelled strategy stays cancelled instead of failing the lookup`(): Unit = runBlocking {
        val resolver = missingClassResolver { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { resolver.findDefinitionAt(uri, position) }
    }

    @Test
    fun `negative coordinates are not found, not a broken lookup`(): Unit = runBlocking {
        val lookup = resolver("def value = 1\n").findDefinitionAt(uri, Position(-1, 0))
        assertIs<DefinitionResolver.DefinitionLookup.NotFound>(lookup, "lookup: $lookup")
    }

    @Test
    fun `a string literal is not found, not a broken lookup`(): Unit = runBlocking {
        val lookup = resolver("println \"literal\"\n").findDefinitionAt(uri, Position(0, 10))
        val notFound = assertIs<DefinitionResolver.DefinitionLookup.NotFound>(lookup, "lookup: $lookup")
        assertEquals("position", notFound.error.source)
    }

    @Test
    fun `an unexpected failure propagates instead of becoming an unknown symbol`(): Unit = runBlocking {
        val resolver = resolver("def value = 1\nprintln value\n")
        every { compilation.getAst(uri) } throws IllegalStateException("ast unavailable")

        val failure = assertFailsWith<IllegalStateException> { resolver.findDefinitionAt(uri, Position(1, 9)) }
        assertEquals("ast unavailable", failure.message)
    }
}
