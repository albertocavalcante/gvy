// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.symbols

import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.services.GroovyWorkspaceService
import com.github.albertocavalcante.gvy.source.DocumentVersion
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION") // The wire route returns SymbolInformation.
class WorkspaceSymbolDeliveryTest {
    enum class Finish { CURRENT, SAME_TEXT_REOPEN, INDEX_PUBLICATION, CLASSPATH }

    @ParameterizedTest
    @EnumSource(Finish::class)
    fun `the converted workspace symbol and its publication stay current until delivery`(
        finish: Finish,
        @TempDir root: Path,
    ) {
        val uri = root.resolve("Target.groovy").toUri()
        val line = "    String face = '😀'; void uniqueMethod() {}"
        val source = "class Target {\n$line\n}\n"
        val version = DocumentVersion.of(7)
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val text =
            GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )
        val workspace = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, scope, text)
        val projected = CompletableFuture<SymbolInformation>()
        val release = CountDownLatch(1)
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, version)
            val original = assertNotNull(documents.getSnapshot(uri))
            val expected = Range(Position(1, line.indexOf("void")), Position(1, line.length))
            mockkStatic(Symbol::toSymbolInformation) {
                every { any<Symbol>().toSymbolInformation(any()) } answers {
                    val result = callOriginal()
                    if (result?.name == "uniqueMethod") {
                        projected.complete(result)
                        check(release.await(30, TimeUnit.SECONDS)) { "symbol conversion was never released" }
                    }
                    result
                }
                val pending = workspace.symbol(WorkspaceSymbolParams("uniqueMethod"))
                try {
                    val symbol = projected.get(30, TimeUnit.SECONDS)
                    assertEquals(uri.toString(), symbol.location.uri)
                    assertEquals(expected, symbol.location.range)
                    when (finish) {
                        Finish.SAME_TEXT_REOPEN -> {
                            documents.remove(uri)
                            compilation.onClosedSourceChanged(uri, keepIfUnchanged = false)
                            compilation.onDocumentOpened(uri)
                            documents.put(uri, source, version)
                            assertTrue(original !== documents.getSnapshot(uri))
                        }
                        Finish.INDEX_PUBLICATION -> {
                            val file = Files.writeString(
                                root.resolve("Other.groovy"),
                                "class Other { void uniqueMethod() {} }",
                            )
                            assertNotNull(runBlocking { compilation.symbolIndexingService.indexFile(file.toUri()) })
                        }
                        Finish.CLASSPATH -> {
                            val dependency = Files.createDirectory(root.resolve("dependency"))
                            compilation.updateWorkspaceModel(root, listOf(dependency), listOf(root))
                            assertTrue(dependency in compilation.workspaceManager.current().classpath)
                        }
                        Finish.CURRENT -> Unit
                    }
                } finally {
                    release.countDown()
                }
                if (finish == Finish.CURRENT) {
                    val result = pending.get(30, TimeUnit.SECONDS).left.single()
                    assertEquals(projected.get(), result)
                    assertEquals(expected, result.location.range)
                } else {
                    val failure = assertFailsWith<ExecutionException> { pending.get(30, TimeUnit.SECONDS) }
                    assertEquals(
                        ResponseErrorCode.ContentModified.value,
                        assertIs<ResponseErrorException>(failure.cause).responseError.code,
                    )
                }
            }
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
