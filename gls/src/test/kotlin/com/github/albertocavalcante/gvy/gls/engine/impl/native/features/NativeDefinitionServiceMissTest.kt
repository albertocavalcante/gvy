// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.engine.impl.native.features

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.engine.adapters.ParseUnit
import com.github.albertocavalcante.gvy.gls.engine.adapters.UnifiedNode
import com.github.albertocavalcante.gvy.gls.engine.adapters.UnifiedSymbol
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/** A cursor that names no symbol is "no definition" on the engine's path too, exactly as on the LSP path. */
class NativeDefinitionServiceMissTest {
    private val uri = URI.create("file:///test/Miss.groovy")
    private val compilation = GroovyCompilationService()
    private val content = "println \"literal\"\n\n   \n"

    private val unit = object : ParseUnit {
        override val uri: String = this@NativeDefinitionServiceMissTest.uri.toString()
        override val isSuccessful: Boolean = true
        override val diagnostics: List<Diagnostic> = emptyList()
        override fun nodeAt(position: Position): UnifiedNode? = null
        override fun allSymbols(): List<UnifiedSymbol> = emptyList()
    }

    private fun definitionsAt(position: Position) = runBlocking {
        compilation.compile(uri, content)
        NativeDefinitionService(compilation, null).findDefinition(null, unit, position)
    }

    @Test
    fun `a cursor on a string literal finds no definition`() {
        assertTrue(definitionsAt(Position(0, 10)).isEmpty())
    }

    @Test
    fun `a cursor on whitespace finds no definition`() {
        assertTrue(definitionsAt(Position(2, 1)).isEmpty())
    }
}
