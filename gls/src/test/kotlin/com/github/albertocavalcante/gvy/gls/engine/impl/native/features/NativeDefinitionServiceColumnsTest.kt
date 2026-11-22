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
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The engine receives protocol positions (UTF-16 columns) and must resolve them against code-point columns. */
class NativeDefinitionServiceColumnsTest {
    private val uri = URI.create("file:///test/Emoji.groovy")
    private val compilation = GroovyCompilationService()

    private val unit = object : ParseUnit {
        override val uri: String = this@NativeDefinitionServiceColumnsTest.uri.toString()
        override val isSuccessful: Boolean = true
        override val diagnostics: List<Diagnostic> = emptyList()
        override fun nodeAt(position: Position): UnifiedNode? = null
        override fun allSymbols(): List<UnifiedSymbol> = emptyList()
    }

    @Test
    fun `definition resolves a symbol after supplementary characters on the line`() {
        val content = "def x = 1\ndef s = '😀😀'; println x"
        assertTrue(runBlocking { compilation.compile(uri, content) }.isSuccess, "Compilation should succeed")
        val service = NativeDefinitionService(compilation, null)

        // Each emoji is two UTF-16 units but one Groovy column.
        val column = content.lines()[1].lastIndexOf('x')
        val definitions = runBlocking { service.findDefinition(null, unit, Position(1, column)) }

        assertEquals(listOf(0), definitions.map { it.range.start.line })
    }

    @Test
    fun `definition ranges use UTF-16 columns after supplementary characters on the target line`() {
        // The emoji pair and "abcd" occupy the same UTF-16 columns, but Groovy counts the emoji as two columns.
        fun rangeFor(name: String, filler: String): Range {
            val fileUri = URI.create("file:///$name.groovy")
            val content = "def s = '$filler'; def x = 1; println x"
            assertTrue(runBlocking { compilation.compile(fileUri, content) }.isSuccess, "Compilation should succeed")
            val parseUnit = object : ParseUnit by unit {
                override val uri: String = fileUri.toString()
            }
            val service = NativeDefinitionService(compilation, null)
            val position = Position(0, content.lastIndexOf('x'))
            return runBlocking { service.findDefinition(null, parseUnit, position) }.single().range
        }

        assertEquals(rangeFor("ascii", "abcd"), rangeFor("emoji", "\uD83D\uDE00\uD83D\uDE00"))
    }
}
