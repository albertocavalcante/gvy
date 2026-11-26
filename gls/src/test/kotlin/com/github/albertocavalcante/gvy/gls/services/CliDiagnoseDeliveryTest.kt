// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.DiagnosticConfig
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.defaultWorkerDescriptors
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserRange
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliDiagnoseDeliveryTest {
    enum class Finish { CURRENT, PARSE_RETIRED_EMPTY, PROVIDER_CONFIG_RETIRED, PROVIDER_CANCELLED }

    @ParameterizedTest
    @EnumSource(Finish::class)
    fun `CLI diagnostics validate the parser and registered provider at delivery`(
        finish: Finish,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val empty = finish == Finish.PARSE_RETIRED_EMPTY
        val source = if (empty) "class Checked {}" else SOURCE
        val uri = Files.writeString(root.resolve("Checked.groovy"), source).toUri()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun pause() {
            reached.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "diagnostic producer was never released" }
        }
        val compilation = GroovyCompilationService(workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    val parsed = delegate.parse(request)
                    assertEquals(source, request.content)
                    assertTrue(parsed.isSuccessful)
                    assertNotNull(parsed.ast)
                    if (empty) {
                        pause()
                        return parsed
                    }
                    // Observe only this parser's annotation map. Its real range is read by the registered
                    // unused-import provider after it has checked this module for unused imports.
                    val annotations = spyk(parsed.sourceAnnotations)
                    every { annotations.importRange(any()) } answers {
                        val actual = callOriginal()
                        assertEquals(ParserRange.singleLine(0, 0, IMPORT.length), actual)
                        pause()
                        actual
                    }
                    return parsed.copy(sourceAnnotations = annotations)
                }
            }
        })
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val configuration = ServerConfiguration(diagnosticConfig = DiagnosticConfig(disabledProviders = DISABLED))
        val options = GroovyTextDocumentServiceOptions(serverConfiguration = configuration)
        val endpoint = GroovyTextDocumentService(scope, compilation, options)
        try {
            val pending = scope.async { endpoint.diagnose(uri, source) }
            try {
                assertTrue(reached.await(30, TimeUnit.SECONDS), "real parse/provider did not reach its barrier")
                DiagnosticFinish.apply(finish, compilation, configuration, endpoint, pending)
            } finally {
                release.countDown()
            }
            DiagnosticFinish.verify(finish, pending, ::assertExactUnusedImport)()
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            compilation.shutdown()
        }
    }

    @Test
    fun `ordinary LRU eviction leaves the held CLI import diagnostic available`(@TempDir root: Path) {
        runBlocking {
            val uri = Files.writeString(root.resolve("Checked.groovy"), SOURCE).toUri()
            val neighbor = Files.writeString(root.resolve("Neighbor.groovy"), "class Neighbor {}").toUri()
            val compilation = GroovyCompilationService(compilationCacheMaxEntries = 1)
            val owner = SupervisorJob()
            val selected = CountDownLatch(1)
            val release = CountDownLatch(1)
            val disabled = object : Set<String> by DISABLED {
                override fun contains(element: String): Boolean {
                    if (element == "unused-imports") {
                        selected.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) {
                            "registered provider selection was never released"
                        }
                    }
                    return element in DISABLED
                }
            }
            val scope = CoroutineScope(owner + Dispatchers.Default)
            val config = ServerConfiguration(diagnosticConfig = DiagnosticConfig(disabledProviders = disabled))
            val options = GroovyTextDocumentServiceOptions(serverConfiguration = config)
            val endpoint = GroovyTextDocumentService(scope, compilation, options)
            try {
                val pending = scope.async { endpoint.diagnose(uri, SOURCE) }
                try {
                    assertTrue(
                        selected.await(30, TimeUnit.SECONDS),
                        "request did not select the registered unused-import provider",
                    )
                    assertTrue(compilation.compile(neighbor, "class Neighbor {}").isSuccess)
                    assertNotNull(compilation.getCompiledSnapshot(neighbor))
                    assertNull(
                        compilation.getCompiledSnapshot(uri),
                        "target must be unavailable through the legacy cache",
                    )
                } finally {
                    release.countDown()
                }
                assertExactUnusedImport(pending.await().single())
            } finally {
                release.countDown()
                owner.cancelAndJoin()
                compilation.shutdown()
            }
        }
    }

    private fun assertExactUnusedImport(diagnostic: Diagnostic) {
        assertEquals("unused-import", diagnostic.code.left)
        assertEquals("Unused import: java.util.HashMap as NeverUsed", diagnostic.message)
        assertEquals(DiagnosticSeverity.Hint, diagnostic.severity)
        assertEquals(listOf(DiagnosticTag.Unnecessary), diagnostic.tags)
        assertEquals(Range(Position(0, 0), Position(0, IMPORT.length)), diagnostic.range)
    }

    private companion object {
        const val IMPORT = "import java.util.HashMap as NeverUsed"
        const val SOURCE = "$IMPORT\n\nclass Checked {}"
        val DISABLED = setOf("parser", "custom-rules", "codenarc")
    }
}

private object DiagnosticFinish {
    fun apply(
        finish: CliDiagnoseDeliveryTest.Finish,
        compilation: GroovyCompilationService,
        configuration: ServerConfiguration,
        endpoint: GroovyTextDocumentService,
        pending: Deferred<List<Diagnostic>>,
    ) {
        when (finish) {
            CliDiagnoseDeliveryTest.Finish.PARSE_RETIRED_EMPTY -> {
                val worker = defaultWorkerDescriptors().single().copy(id = "diagnostic-replacement")
                assertTrue(compilation.updateSelectedWorker(worker))
            }
            CliDiagnoseDeliveryTest.Finish.PROVIDER_CONFIG_RETIRED -> {
                val changed = configuration.copy(maxNumberOfProblems = 0)
                endpoint.updateConfiguration(changed)
                assertEquals(0, compilation.serverConfiguration.maxNumberOfProblems)
            }
            CliDiagnoseDeliveryTest.Finish.PROVIDER_CANCELLED -> pending.cancel()
            CliDiagnoseDeliveryTest.Finish.CURRENT -> Unit
        }
    }

    fun verify(
        finish: CliDiagnoseDeliveryTest.Finish,
        pending: Deferred<List<Diagnostic>>,
        assertExactUnusedImport: (Diagnostic) -> Unit,
    ): suspend () -> Unit = when (finish) {
        CliDiagnoseDeliveryTest.Finish.CURRENT -> suspend { assertExactUnusedImport(pending.await().single()) }
        CliDiagnoseDeliveryTest.Finish.PROVIDER_CANCELLED -> suspend {
            assertFailsWith<CancellationException> { pending.await() }
        }
        else -> suspend {
            val failure = assertFailsWith<ResponseErrorException> { pending.await() }
            assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
        }
    }
}
