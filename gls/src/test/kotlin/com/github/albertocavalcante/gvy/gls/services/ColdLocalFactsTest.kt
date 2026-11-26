// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ColdLocalFactsTest {
    enum class Case(val lens: Boolean, val mixedScheme: Boolean) {
        LENS(true, false),
        LENS_MIXED(true, true),
        SYMBOL(false, false),
        SYMBOL_MIXED(false, true),
    }

    @ParameterizedTest
    @EnumSource(Case::class)
    fun `closed local facts compile only their requested source`(case: Case, @TempDir directory: Path) {
        val src = Files.createDirectories(directory.resolve("src"))
        repeat(200) { Files.writeString(src.resolve("Filler$it.groovy"), "class Filler$it {}\n") }
        val file = Files.writeString(src.resolve("Sample.groovy"), SOURCE)
        val normal = file.toUri().toString()
        val uri = URI.create(if (case.mixedScheme) normal.replaceFirst("file:", "FiLe:") else normal)
        val documents = DocumentProvider()
        val observed = AtomicReference<Pair<ParseMode, Int>>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val result = delegate.parse(request)
                        val count = result.compilationUnit.iterator().asSequence()
                            .map { it.name }.count { !isStubSourceName(it) }
                        observed.set(request.parseMode to count)
                        return result
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.updateWorkspaceModel(directory, emptyList(), listOf(src))
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val identifier = TextDocumentIdentifier(uri.toString())
            if (case.lens) {
                val lenses = service.codeLens(CodeLensParams(identifier)).get(20, TimeUnit.SECONDS)
                assertTrue(lenses.any { it.command.command == "groovy.runFile" })
            } else {
                val symbols = service.documentSymbol(DocumentSymbolParams(identifier)).get(20, TimeUnit.SECONDS)
                assertTrue(symbols.any { it.isLeft && it.left.name == "Sample" })
            }
            val (mode, compiledFiles) = assertNotNull(observed.get())
            assertEquals(ParseMode.MINIMAL, mode)
            assertEquals(1, compiledFiles)
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    @ParameterizedTest
    @EnumSource(Case::class, names = ["LENS", "SYMBOL"])
    fun `open local facts retain workspace parse mode`(case: Case, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("Sample.groovy"), SOURCE)
        val uri = file.toUri()
        val documents = DocumentProvider()
        val mode = AtomicReference<ParseMode>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        mode.set(request.parseMode)
                        return delegate.parse(request)
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.onDocumentOpened(uri)
            documents.put(uri, SOURCE, DocumentVersion.of(1))
            val service = GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
            val identifier = TextDocumentIdentifier(uri.toString())
            if (case.lens) {
                assertTrue(service.codeLens(CodeLensParams(identifier)).get(20, TimeUnit.SECONDS).isNotEmpty())
            } else {
                assertTrue(
                    service.documentSymbol(DocumentSymbolParams(identifier)).get(20, TimeUnit.SECONDS).isNotEmpty(),
                )
            }
            assertEquals(ParseMode.WORKSPACE, mode.get())
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private companion object {
        const val SOURCE = "class Sample { static void main(String[] args) {} }\n"
    }
}
