// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SymbolIndexingService
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION") // The workspace symbol wire response uses SymbolInformation.
class IndexedSymbolSelectionTest {
    private val line = "    String face = '😀'; void uniqueMethod() {}"
    private val content = "class Target {\r\n$line\r\n}\r\n/*\uFEFF" + "é😀".repeat(2048) + "*/\r\n"

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a larger closed row needs a fresh index publication`(legacy: Boolean, @TempDir root: Path): Unit =
        runBlocking {
            val file = Files.writeString(root.resolve("Target.groovy"), content)
            val uri = file.toUri()
            val compilation = GroovyCompilationService()
            val index = compilation.symbolIndexingService
            try {
                val original = assertNotNull(index.indexFile(uri))
                if (legacy) {
                    val source = compilation.captureSnapshotSource(uri)
                    assertTrue(source.dependencies.cacheService.ownership.commit(source.ticket) {
                        index.install(uri, SymbolIndexingService.Prepared(content, original))
                    })
                }
                assertEquals(original, index.getSymbolIndex(uri))
                assertLocation(query(compilation).single(), file)
                val (_, published) = index.captureRows()
                Files.writeString(file, "// edit", StandardOpenOption.APPEND)
                val failure = assertFailsWith<ResponseErrorException> { query(compilation) }
                assertTrue(published())
                assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
                assertNotNull(index.indexFile(uri))
                assertLocation(query(compilation).single(), file)
            } finally {
                compilation.shutdown()
            }
        }

    @Test
    fun `a missing row that reappears after a later hit invalidates delivery`(@TempDir root: Path): Unit = runBlocking {
        val absent = Files.writeString(root.resolve("ANegative.groovy"), "class ANegative {}")
        val hit = Files.writeString(root.resolve("Target.groovy"), content)
        val compilation = GroovyCompilationService()
        val index = compilation.symbolIndexingService
        try {
            assertNotNull(index.indexFile(absent.toUri()))
            assertNotNull(index.indexFile(hit.toUri()))
            assertLocation(query(compilation).single(), hit)
            Files.delete(absent)
            assertLocation(query(compilation).single(), hit)
            var projected = false
            mockkStatic(Symbol::toSymbolInformation) {
                every { any<Symbol>().toSymbolInformation(any()) } answers {
                    callOriginal()?.also {
                        assertLocation(it, hit)
                        Files.writeString(absent, "// " + "updated ".repeat(2048))
                        projected = true
                    }
                }
                val failure = assertFailsWith<ResponseErrorException> { query(compilation) }
                assertTrue(projected)
                assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
            }
        } finally {
            compilation.shutdown()
        }
    }

    private suspend fun query(compilation: GroovyCompilationService): List<SymbolInformation> =
        WorkspaceSymbolRequest(compilation, compilation.symbolIndexingService, "uniqueMethod", 1).search {}

    private fun assertLocation(hit: SymbolInformation, file: Path) {
        assertEquals("uniqueMethod", hit.name)
        assertEquals(file.toUri().toString(), hit.location.uri)
        assertEquals(Range(Position(1, line.indexOf("void")), Position(1, line.length)), hit.location.range)
    }
}
