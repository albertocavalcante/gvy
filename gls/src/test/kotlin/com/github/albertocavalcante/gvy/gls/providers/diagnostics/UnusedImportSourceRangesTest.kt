// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.codeaction.CodeActionProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.Formatter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UnusedImportSourceRangesTest {
    private val uri = URI.create("file:///ImportRanges.groovy")

    @Test
    fun `unused diagnostic uses editor CR lines instead of Groovy compiler LF lines`() = runBlocking {
        val compilation = GroovyCompilationService(
            parentClassLoader = groovy.transform.CompileStatic::class.java.classLoader,
        )
        try {
            val source = "import groovy.transform.Immutable\r" +
                "import java.util.List\r@Immutable\rclass Money { BigDecimal amount }\r"
            val held = compilation.compile(uri, source)
            val diagnostic = UnusedImportDiagnosticProvider(compilation)
                .provideDiagnostics(uri, source, held)
                .toList()
                .single()
            assertEquals(1, diagnostic.range.start.line)
            assertEquals(0, diagnostic.range.start.character)
            assertEquals(1, diagnostic.range.end.line)
            assertEquals("import java.util.List".length, diagnostic.range.end.character)
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `supplementary characters and tabs before an import preserve exact UTF16 columns`() = runBlocking {
        val compilation = GroovyCompilationService(
            parentClassLoader = groovy.transform.CompileStatic::class.java.classLoader,
        )
        try {
            val source = "/*😀*/\timport java.util.List\rclass Money {}\r"
            val held = compilation.compile(uri, source)
            val diagnostic = UnusedImportDiagnosticProvider(compilation)
                .provideDiagnostics(uri, source, held)
                .toList()
                .single()
            assertEquals(source.indexOf("import"), diagnostic.range.start.character)
            assertEquals(source.indexOf('\r'), diagnostic.range.end.character)
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `unused removal accepts the actual CR line and rejects a used annotation import line`() = runBlocking {
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(
            parentClassLoader = groovy.transform.CompileStatic::class.java.classLoader,
            documentProvider = documents,
        )
        try {
            val source = "import groovy.transform.Immutable\r" +
                "import java.util.List\r@Immutable\rclass Money { BigDecimal amount }\r"
            documents.put(uri, source)
            compilation.compile(uri, source)
            val provider = CodeActionProvider(compilation, documents, Formatter { it })
            suspend fun actionAt(line: Int) = provider.provideCodeActions(
                CodeActionParams(
                    TextDocumentIdentifier(uri.toString()),
                    Range(Position(line, 0), Position(line, 1)),
                    CodeActionContext(
                        listOf(
                            Diagnostic(Range(Position(line, 0), Position(line, 1)), "Unused import").apply {
                                code = Either.forLeft("UnusedImport")
                                this.source = "CodeNarc"
                            },
                        ),
                    ),
                ),
            ).find { it.title == "Remove unused import" }
            assertNotNull(actionAt(1))
            assertNull(actionAt(0))
        } finally {
            compilation.shutdown()
        }
    }
}
