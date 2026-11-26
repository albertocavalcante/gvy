// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverRequestHandler
import com.github.albertocavalcante.gvy.gls.providers.semantictokens.SemanticTokensHandler
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.jenkins.GlobalVariable
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HoverTokensDeliveryFailureTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @CsvSource(
        "hover,cancel", "tokens,cancel", "hover,retire", "tokens,retire",
        "hover,current", "tokens,current", "hover,fatal", "tokens,fatal",
        "hover,cancelFatal", "tokens,cancelFatal",
    )
    fun `throwing final Jenkins qualification preserves cancellation retirement and failures`(
        endpoint: String,
        action: String,
    ): Unit = runBlocking {
        val uri = root.resolve("Example.groovy").toUri()
        val source = "sharedStep()"
        val docs = DocumentProvider().apply { put(uri, source) }
        val manager = spyk(WorkspaceManager())
        val capability = mockk<JenkinsCapabilities>()
        val variable = GlobalVariable("sharedStep", root.resolve("vars/sharedStep.groovy"))
        val read = AtomicBoolean(false)
        val qualified = AtomicBoolean(false)
        val failures = AtomicInteger()
        val io = IOException("failed while rechecking consulted Jenkins variables")
        val fatal = AssertionError("fatal Jenkins validation failure")
        lateinit var caller: Job
        every { manager.getJenkinsCapabilities() } returns capability
        every { capability.isJenkinsFile(any()) } returns true
        every { capability.getAllMetadata(any()) } returns null
        every { manager.compileContextFor(any(), any(), any()) } answers {
            callOriginal().also { if (read.get()) qualified.set(true) }
        }
        every { capability.getGlobalVariables() } answers {
            if (qualified.get()) {
                failures.incrementAndGet()
                when (action) {
                    "cancel", "cancelFatal" -> caller.cancel(
                        CancellationException("cancelled during Jenkins validation"),
                    )
                    "retire", "fatal" -> docs.put(uri, "$source // edited")
                }
                if (action == "fatal" || action == "cancelFatal") throw fatal
                throw io
            }
            read.set(true)
            listOf(variable)
        }
        val service = GroovyCompilationService(documentProvider = docs, workspaceManager = manager)
        try {
            var escaped: Throwable? = null
            launch {
                caller = coroutineContext.job
                escaped = runCatching {
                    if (endpoint == "hover") {
                        val params = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, 1))
                        HoverRequestHandler(service, docs).getHover(params)
                    } else {
                        SemanticTokensHandler(service, docs).getSemanticTokens(uri)
                    }
                }.exceptionOrNull()
            }.join()
            assertTrue(read.get() && qualified.get(), "The compiler must requalify after actual vars consumption")
            assertEquals(1, failures.get())
            assertDeliveryFailure(action, escaped, fatal, io)
        } finally {
            service.shutdown()
        }
    }

    @ParameterizedTest
    @CsvSource("hover", "tokens")
    fun `delivery refuses a classpath change during final Jenkins qualification`(endpoint: String): Unit = runBlocking {
        val classpathA = Files.createDirectory(root.resolve("classpath-a"))
        val classpathB = Files.createDirectory(root.resolve("classpath-b"))
        val uri = root.resolve("Example.groovy").toUri()
        val source = "sharedStep()"
        val docs = DocumentProvider().apply { put(uri, source) }
        val manager = spyk(WorkspaceManager())
        val changed = AtomicBoolean(false)
        manager.setTestClasspath(root) { listOf(if (changed.get()) classpathB else classpathA) }
        val capability = mockk<JenkinsCapabilities>()
        val variables = listOf(GlobalVariable("sharedStep", root.resolve("vars/sharedStep.groovy")))
        val read = AtomicBoolean(false)
        val qualified = AtomicBoolean(false)
        val changes = AtomicInteger()
        every { manager.getJenkinsCapabilities() } returns capability
        every { capability.isJenkinsFile(any()) } returns true
        every { capability.getAllMetadata(any()) } returns null
        every { manager.compileContextFor(any(), any(), any()) } answers {
            callOriginal().also { context ->
                if (read.get() && !changed.get()) {
                    assertEquals(listOf(classpathA), context.classpath)
                    qualified.set(true)
                }
            }
        }
        every { capability.getGlobalVariables() } answers {
            if (qualified.get() && changed.compareAndSet(false, true)) changes.incrementAndGet()
            read.set(true)
            variables
        }
        val requests = Collections.synchronizedList(mutableListOf<List<Path>>())
        val service = GroovyCompilationService(
            documentProvider = docs,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                recordingWorker(delegate, requests)
            },
        )
        val model = manager.current()
        val fingerprint = model.fingerprint
        try {
            val outcome = deliveryAttempt(endpoint, service, docs, uri)
            assertTrue(read.get() && qualified.get(), "Compiler qualification must follow vars consumption")
            assertEquals(1, changes.get(), "The final vars read must switch classpath exactly once")
            assertEquals(listOf(listOf(classpathA)), requests)
            assertSame(model, manager.current())
            assertEquals(fingerprint, manager.current().fingerprint)
            assertEquals(listOf(classpathB), manager.compileContextFor(uri, source, model).classpath)
            val failure = assertIs<ResponseErrorException>(outcome.exceptionOrNull())
            assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
        } finally {
            service.shutdown()
        }
    }

    private suspend fun deliveryAttempt(
        endpoint: String,
        service: GroovyCompilationService,
        docs: DocumentProvider,
        uri: URI,
    ): Result<Any?> = runCatching {
        if (endpoint == "hover") {
            HoverRequestHandler(service, docs).getHover(
                HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, 1)),
            )
        } else {
            SemanticTokensHandler(service, docs).getSemanticTokens(uri)
        }
    }

    private fun recordingWorker(delegate: WorkerSession, requests: MutableList<List<Path>>): WorkerSession =
        object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                requests.add(request.classpath.toList())
                return delegate.parse(request)
            }
        }

    private fun assertDeliveryFailure(action: String, escaped: Throwable?, fatal: AssertionError, io: IOException) {
        when (action) {
            "cancel" -> assertIs<CancellationException>(escaped)
            "fatal", "cancelFatal" -> assertSame(
                fatal,
                escaped,
                "Recovered wrapper points to original: ${escaped?.cause === fatal}",
            )
            "retire" -> {
                val error = assertIs<ResponseErrorException>(escaped)
                assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
            }
            "current" -> {
                val error = assertIs<ResponseErrorException>(escaped)
                assertEquals(ResponseErrorCode.InternalError.value, error.responseError.code)
                assertContains(error.responseError.message, requireNotNull(io.message))
            }
            else -> error("Unknown test action $action")
        }
    }
}
