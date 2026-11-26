// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.google.gson.Gson
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForcedHoverThirtyTest {
    @TempDir lateinit var workspace: Path
    private val json = Gson()

    @Test
    fun `forced retired producer hover thirty times`() {
        val outcomes = (1..30).map { trial(it) }
        val counts = outcomes.groupingBy { it }.eachCount()
        emit(mapOf("kind" to "aggregate", "trials" to outcomes.size, "counts" to counts))
        val control = positiveControl()
        emit(mapOf("kind" to "control", "category" to control))
        assertEquals(30, outcomes.size)
        assertTrue(outcomes.all { it == "typed-hover" || it == "content-modified" }, counts.toString())
        assertEquals("typed-hover", control)
    }

    private fun trial(number: Int): String {
        val dir = Files.createDirectories(workspace.resolve("trial-$number"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val barrierFailure = AtomicReference<String?>(null)
        val events = mutableMapOf<String, Any>(
            "kind" to "trial", "number" to number, "mode" to "forced-api-direct-document",
        )
        val server = serverWithBarrier(first, entered, release, exited, barrierFailure, events)
        var category = "other-error"
        try {
            val uri = dir.resolve("Inference.groovy").toUri().toString()
            events["uri"] = uri
            initialize(server, dir)
            server.compilationService.initializeWorkspace(dir)
            val documentUri = URI.create(uri)
            server.compilationService.onDocumentOpened(documentUri)
            server.compilationService.documents.put(documentUri, SOURCE, DocumentVersion.of(1))
            // capture() runs synchronously inside hover(), before the returned future is scheduled.
            val answer = server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 5)))
            check(entered.await(30, TimeUnit.SECONDS)) { "actual parse barrier missed" }
            check(barrierFailure.get() == null) { "native parse or barrier failed: ${barrierFailure.get()}" }
            check(events["parseUri"] == uri && events["parseInput"] == SOURCE &&
                events["parsePhase"] == Phases.CANONICALIZATION && events["parseMode"] == "WORKSPACE" &&
                events["requestedPhase"] == Phases.CANONICALIZATION &&
                events["achievedPhase"] == Phases.CANONICALIZATION &&
                events["parseResult"] == "successful-undegraded-ast"
            ) { "wrong parse entered barrier" }
            retire(server, dir, events)
            check(exited.count == 1L && release.count == 1L && barrierFailure.get() == null) {
                "completed parse did not remain held across both retirements: ${barrierFailure.get()}"
            }
            events["heldAfterBothRetirements"] = true
            release.countDown()
            category = classify(events) { answer.get(30, TimeUnit.SECONDS) }
        } catch (failure: Throwable) {
            events["failure"] = "${failure.javaClass.name}: ${failure.message}"
        } finally {
            category = finishTrial(server, first, release, exited, barrierFailure, events, category)
            events["category"] = category
            emit(events)
        }
        return category
    }

    private fun finishTrial(
        server: GroovyLanguageServer,
        first: AtomicBoolean,
        release: CountDownLatch,
        exited: CountDownLatch,
        barrierFailure: AtomicReference<String?>,
        events: MutableMap<String, Any>,
        initialCategory: String,
    ): String {
        var category = initialCategory
        release.countDown()
        try {
            if (!first.get()) {
                check(exited.await(30, TimeUnit.SECONDS)) { "held parse did not exit" }
                events["parseExited"] = true
                check(barrierFailure.get() == null) { "barrier failed: ${barrierFailure.get()}" }
            }
        } catch (failure: Throwable) {
            events["barrierFailure"] = "${failure.javaClass.name}: ${failure.message}"
            category = "other-error"
        }
        try {
            server.shutdown().get(30, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            events["shutdownFailure"] = "${failure.javaClass.name}: ${failure.message}"
            category = "other-error"
        }
        return category
    }

    private fun serverWithBarrier(
        first: AtomicBoolean,
        entered: CountDownLatch,
        release: CountDownLatch,
        exited: CountDownLatch,
        barrierFailure: AtomicReference<String?>,
        events: MutableMap<String, Any>,
    ): GroovyLanguageServer = GroovyLanguageServer { delegate ->
        object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                if (!first.compareAndSet(true, false)) return delegate.parse(request)
                try {
                    val result = delegate.parse(request)
                    check(result.ast != null && result.isSuccessful && !result.outcome.isDegraded) {
                        "native parse did not produce a successful undegraded AST"
                    }
                    events["parseUri"] = request.uri.toString()
                    events["parsePhase"] = request.compilePhase
                    events["parseMode"] = request.parseMode.toString()
                    events["parseInput"] = request.content
                    events["parseResult"] = "successful-undegraded-ast"
                    events["requestedPhase"] = result.outcome.requestedPhase
                    events["achievedPhase"] = result.outcome.achievedPhase
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "parse release timed out" }
                    return result
                } catch (failure: Throwable) {
                    barrierFailure.compareAndSet(null, "${failure.javaClass.name}: ${failure.message}")
                    entered.countDown()
                    throw failure
                } finally {
                    exited.countDown()
                }
            }
        }
    }

    private fun retire(server: GroovyLanguageServer, dir: Path, events: MutableMap<String, Any>) {
        val service = server.compilationService
        val oldWorker = service.getSelectedWorker()?.id ?: "default"
        val worker = WorkerDescriptor(
            id = "replacement",
            supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("5.0.0")),
            capabilities = WorkerCapabilities(), connector = WorkerConnector.InProcess,
        )
        check(service.updateSelectedWorker(worker)) { "worker did not change" }
        events["workerBefore"] = oldWorker
        events["workerAfter"] = checkNotNull(service.getSelectedWorker()).id
        val oldRoots = service.workspaceManager.current().sourceRoots.map { it.toString() }
        val newRoot = Files.createDirectories(dir.resolve("changed-source-root"))
        service.classpathService.acquireClasspath().use { oldClasspath ->
            service.updateWorkspaceModel(dir, emptyList(), listOf(newRoot))
            check(!oldClasspath.isCurrent()) { "model update did not retire classpath" }
        }
        val newRoots = service.workspaceManager.current().sourceRoots.map { it.toString() }
        check(oldRoots != newRoots && newRoots.contains(newRoot.toString())) { "model did not change" }
        events["modelRootsBefore"] = oldRoots
        events["modelRootsAfter"] = newRoots
        events["retirements"] = listOf("worker", "model")
    }

    private fun positiveControl(): String {
        val dir = Files.createDirectories(workspace.resolve("control"))
        val server = GroovyLanguageServer()
        val client = SynchronizingTestLanguageClient()
        server.connect(client)
        return try {
            initialize(server, dir)
            server.initialized(InitializedParams())
            client.awaitMessageMatching { it.message.startsWith("Dependencies loaded:") }
            val uri = dir.resolve("Inference.groovy").toUri().toString()
            open(server, uri)
            client.awaitSuccessfulCompilation(uri)
            val controlEvents = mutableMapOf<String, Any>(
                "kind" to "control-response", "uri" to uri, "producer" to "successful-diagnostics-after-ready",
            )
            val category = classify(controlEvents) {
                server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), Position(0, 5)))
                    .get(30, TimeUnit.SECONDS)
            }
            controlEvents["category"] = category
            emit(controlEvents)
            category
        } catch (failure: Throwable) {
            emit(mapOf("kind" to "control-error", "error" to "${failure.javaClass.name}: ${failure.message}"))
            "other-error"
        } finally {
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }

    private fun classify(events: MutableMap<String, Any>, answer: () -> Hover): String = try {
        val result = answer()
        val contents = result.contents
        events["response"] = contents.toString()
        if (contents.isRight && contents.right.kind == MarkupKind.MARKDOWN &&
            contents.right.value.contains("int count")
        ) "typed-hover" else "wrong-or-empty-hover"
    } catch (failure: ExecutionException) {
        val cause = failure.cause as? ResponseErrorException
        events["responseError"] = "${failure.cause?.javaClass?.name}: ${failure.cause?.message}"
        if (cause?.responseError?.code == ResponseErrorCode.ContentModified.value) "content-modified" else "other-error"
    } catch (failure: TimeoutException) {
        events["responseError"] = failure.toString()
        "timeout"
    } catch (failure: CancellationException) {
        events["responseError"] = failure.toString()
        "cancelled"
    }

    private fun initialize(server: GroovyLanguageServer, dir: Path) {
        server.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(dir.toUri().toString(), "hover"))
            capabilities = ClientCapabilities()
        }).get(30, TimeUnit.SECONDS)
    }

    private fun open(server: GroovyLanguageServer, uri: String) {
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, SOURCE)))
    }

    private fun emit(record: Map<String, Any>) = println("HOVER_THIRTY_JSON ${json.toJson(record)}")

    private companion object { const val SOURCE = "def count = 42" }
}
