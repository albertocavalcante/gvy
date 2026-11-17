// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Definition derives its text, AST model and symbol table from one cache entry, never from separate reads. */
class DefinitionSnapshotTest {
    private val uri = URI.create("file:///snapshot.groovy")
    private val content = "def x = 1\ndef s = '😀😀'; println x"

    @Test
    fun `definition ignores the separate AST accessors that a recompile could change`(): Unit = runBlocking {
        val service = spyk(GroovyCompilationService())
        assertTrue(service.compile(uri, content).isSuccess, "Compilation should succeed")

        // Another generation of the same document: the symbol is declared on a different line.
        val otherUri = URI.create("file:///other-generation.groovy")
        val other = GroovyCompilationService()
        assertTrue(other.compile(otherUri, "\n\n\ndef s = '😀😀'; println x").isSuccess)
        every { service.getAst(uri) } returns other.getAst(otherUri)
        every { service.getAstModel(uri) } returns other.getAstModel(otherUri)
        every { service.getSymbolTable(uri) } returns other.getSymbolTable(otherUri)

        val column = content.lines()[1].lastIndexOf('x')
        val definitions = DefinitionProvider(service).provideDefinitions(uri.toString(), Position(1, column)).toList()

        assertEquals(listOf(0), definitions.map { it.range.start.line })
    }
}
