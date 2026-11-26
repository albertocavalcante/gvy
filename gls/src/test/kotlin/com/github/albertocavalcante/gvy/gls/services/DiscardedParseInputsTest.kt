// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.DiagnosticConfig
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pauses/mutates inside the actual selected CLI worker, after its last retry and before any delivery validation. */
class DiscardedParseInputsTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @ValueSource(strings = ["current", "edited", "opened", "cancelled", "unreadable"])
    fun `diagnostics retain the failed disk reader that caused exclusion`(finish: String): Unit = runBlocking {
        val broken = Files.writeString(root.resolve("Broken.groovy"), "class Broken { void m( }")
        val time = Files.getLastModifiedTime(broken)
        val documents = DocumentProvider()
        val owner = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + owner)
        val reached = AtomicBoolean()
        val compiler = compiler(documents) { request, parsed ->
            assertTrue(broken in request.workspaceSources)
            assertEquals(listOf(broken.toString()), parsed.outcome.excludedSources.map { it.source })
            assertTrue(parsed.outcome.excludedSources.single().reason.isNotBlank())
            assertNotNull(parsed.ast)
            reached.set(true)
            if (finish == "edited" || finish == "cancelled") {
                // Neither length nor mtime can distinguish these two failing programs.
                Files.writeString(broken, "class Broken { void n( }")
                Files.setLastModifiedTime(broken, time)
            }
            if (finish == "opened") documents.put(broken.toUri(), "class Broken { void m( }")
            if (finish == "unreadable") {
                Files.delete(broken)
                Files.createSymbolicLink(broken, broken.fileName)
            }
            if (finish == "cancelled") owner.cancel()
        }
        try {
            val endpoint = endpoint(scope, compiler)
            val task = scope.async { endpoint.diagnose(root.resolve("Uses.groovy").toUri(), SOURCE) }
            if (finish == "current") {
                val diagnostic = task.await().single { it.code?.left == "unused-import" }
                assertEquals("Unused import: java.util.HashMap as NeverUsed", diagnostic.message)
            } else if (finish == "cancelled") {
                assertFailsWith<CancellationException> { task.await() }
            } else if (finish == "unreadable") {
                assertFailsWith<IOException> { task.await() }
            } else {
                val error = assertFailsWith<ResponseErrorException> { task.await() }
                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            }
            assertTrue(reached.get())
        } finally {
            owner.cancelAndJoin()
            compiler.shutdown()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `diagnostics retain the real negative lookup replaced by a synthetic class`(appears: Boolean): Unit =
        runBlocking {
            val candidate = root.resolve("LateType.groovy")
            val reached = AtomicBoolean()
            val compiler = compiler(DocumentProvider()) { _, parsed ->
                assertTrue("LateType" in parsed.outcome.stubbedClasses)
                assertTrue(parsed.diagnostics.any { it.message.contains("unable to resolve class LateType") })
                if (appears) Files.writeString(candidate, "class LateType {}")
                reached.set(true)
            }
            try {
                val service = endpoint(this, compiler)
                val uri = root.resolve("Uses.groovy").toUri()
                val source = "class Uses { LateType field }"
                if (appears) {
                    val error = assertFailsWith<ResponseErrorException> { service.diagnose(uri, source) }
                    assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
                } else {
                    assertTrue(service.diagnose(uri, source).any {
                        it.severity == DiagnosticSeverity.Error && it.message.contains("unable to resolve class LateType")
                    })
                }
                assertTrue(reached.get())
            } finally {
                compiler.shutdown()
            }
        }

    @Test
    fun `a primary syntax error remains available without a complete module`(): Unit = runBlocking {
        val compiler = compiler(DocumentProvider()) { _, result -> assertTrue(!result.isSuccessful) }
        try {
            val diagnostics = endpoint(this, compiler).diagnose(root.resolve("Uses.groovy").toUri(), "class Uses { void m( }")
            assertTrue(diagnostics.any { it.severity == DiagnosticSeverity.Error && it.code?.left != "compilation-error" })
        } finally {
            compiler.shutdown()
        }
    }

    private fun compiler(documents: DocumentProvider, after: (ParseRequest, ParseResult) -> Unit) =
        GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also { after(request, it) }
            }
        }).apply { updateWorkspaceModel(root, emptyList(), listOf(root)) }

    private fun endpoint(scope: CoroutineScope, compiler: GroovyCompilationService): GroovyTextDocumentService {
        val configuration = ServerConfiguration(
            diagnosticConfig = DiagnosticConfig(disabledProviders = setOf("codenarc", "custom-rules")),
        )
        return GroovyTextDocumentService(scope, compiler, GroovyTextDocumentServiceOptions(serverConfiguration = configuration))
    }

    private companion object {
        const val SOURCE = "import java.util.HashMap as NeverUsed\nclass Uses {}"
    }
}
