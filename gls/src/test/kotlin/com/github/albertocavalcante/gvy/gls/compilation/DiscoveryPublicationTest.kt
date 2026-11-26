// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.providers.testing.TestDiscoveryProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.junit.JUnit5TestDetector
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiscoveryPublicationTest {
    @Test
    fun `an edit published during symbol construction keeps the newer global symbols`(@TempDir root: Path): Unit =
        runBlocking {
            val original = "class Before {}"
            val replacement = "class After {}"
            val path = Files.writeString(root.resolve("Subject.groovy"), original)
            val uri = path.toUri()
            val documents = DocumentProvider().apply { put(uri, original, DocumentVersion.of(1)) }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val compilation = GroovyCompilationService(
                documentProvider = documents,
                workerSessionDecorator = { worker ->
                    worker.inspect { request, result ->
                        if (request.content == original) holdSymbolBuild(result, entered, release) else result
                    }
                },
            )
            // Hold the debounced diagnostics job. The real edit is compiled to completion explicitly below.
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
            try {
                compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
                compilation.onDocumentOpened(uri)
                val window = DiscoveryWindow(compilation.captureSnapshotSource(root.toUri()))
                val discovery = async(Dispatchers.Default) {
                    compilation.queryUncachedTestDiscovery(uri, window) { "old" }
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS), "Older symbol construction must reach the barrier")
                val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
                GroovyTextDocumentService(scope, compilation, options).didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri.toString(), 2),
                        listOf(TextDocumentContentChangeEvent(replacement)),
                    ),
                )
                val current = assertNotNull(documents.getSnapshot(uri))
                assertEquals(replacement, current.content)
                assertTrue(compilation.compile(uri, replacement).isSuccess)
                val symbols = compilation.symbolIndexingService
                assertTrue(symbols.isIndexed(uri, current))
                assertEquals(listOf(path), symbols.classLocator(null)("After"))
                release.countDown()
                assertIs<SnapshotQuery.Stale>(withTimeout(10_000) { discovery.await() })
                assertTrue(symbols.isIndexed(uri, current), "The stale index build overwrote the current buffer")
                assertEquals(listOf(path), symbols.classLocator(null)("After"))
                assertTrue(symbols.classLocator(null)("Before").isEmpty())
            } finally {
                release.countDown()
                scope.cancel()
                compilation.shutdown()
            }
        }

    private fun holdSymbolBuild(result: ParseResult, entered: CountDownLatch, release: CountDownLatch): ParseResult {
        val model = result.astModel
        return result.copy(
            astModel = object : GroovyAstModel by model {
                override fun getAllClassNodes(): List<ClassNode> {
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Symbol construction was never released" }
                    return model.getAllClassNodes()
                }
            },
        )
    }

    @Test
    fun `script recovery keeps its compiler inputs and installs the recovered symbols`(@TempDir root: Path): Unit =
        runBlocking {
            val source = "class Recovered { @org.junit.jupiter.api.Test void recoveredTest() {} }"
            val path = Files.writeString(root.resolve("Subject.groovy"), source)
            val uri = path.toUri()
            val original = listOf(Files.createDirectory(root.resolve("original-classes")))
            val replacement = listOf(Files.createDirectory(root.resolve("other-classes")))
            val manager = spyk(WorkspaceManager())
            manager.setTestClasspath(root) { original }
            // Only a fresh strategy lookup for the recovery would select the other classpath.
            every { manager.getClasspathForFile(uri, source, any()) } returns replacement
            lateinit var canonical: ParseRequest
            lateinit var recovery: ParseRequest
            val compilation = GroovyCompilationService(
                parentClassLoader = DiscoveryPublicationTest::class.java.classLoader,
                workspaceManager = manager,
                workerSessionDecorator = { worker ->
                    object : WorkerSession {
                        override fun parse(request: ParseRequest): ParseResult {
                            if (request.compilePhase == Phases.CONVERSION) {
                                recovery = request
                                return worker.parse(request)
                            }
                            canonical = request
                            // Reproduce the compiler's filename-shaped Script fallback using a real script model.
                            return worker.parse(request.copy(content = "def placeholder() {}"))
                        }
                    }
                },
            )
            try {
                compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
                val registry = TestFrameworkRegistry().apply { registerIfAbsent(JUnit5TestDetector()) }
                val suites = TestDiscoveryProvider(compilation, registry).discoverTests(root.toUri().toString())
                val suite = suites.single()
                assertEquals("Recovered", suite.suite)
                assertEquals("recoveredTest", suite.tests.single().test)
                assertEquals(source, canonical.content)
                assertEquals(source, recovery.content)
                assertEquals(original, canonical.classpath)
                assertTrue(recovery.sourceRoots.isEmpty() && recovery.workspaceSources.isEmpty())
                val index = assertNotNull(compilation.getSymbolStorage(uri))
                val names = index.getSymbols(uri).map { it.name }
                val locate = compilation.symbolIndexingService.classLocator(null)
                assertAll(
                    { assertEquals(original, recovery.classpath) },
                    { assertEquals(listOf(path), locate("Recovered")) },
                    { assertTrue(locate("Subject").isEmpty()) },
                    { assertTrue("recoveredTest" in names) },
                    { assertTrue("placeholder" !in names) },
                )
            } finally {
                compilation.shutdown()
            }
        }

    private fun WorkerSession.inspect(after: (ParseRequest, ParseResult) -> ParseResult): WorkerSession {
        val delegate = this
        return object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult = after(request, delegate.parse(request))
        }
    }
}
