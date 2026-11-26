// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.ref.WeakReference
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceEnvironmentTest {
    @Test
    fun `retained index provenance has no strong reference to the worker`() {
        val worker = object : WorkerSession {
            override fun parse(request: ParseRequest): Nothing =
                error("No parse expected")
        }
        val producer = ReferenceProducer(emptyList(), worker)
        val directlyOwnedWorker = producer.javaClass.declaredFields.any { field ->
            field.trySetAccessible() && field.get(producer) === worker
        }
        assertFalse(directlyOwnedWorker, "an index entry can outlive its original parser")

        val identity = WeakReference<WorkerSession>(worker)
        val first = ReferenceProducer(emptyList(), identity)
        val second = ReferenceProducer(emptyList(), identity)
        assertTrue(first.sameEnvironment(second))
        identity.clear()
        assertFalse(first.matches(emptyList(), worker))
        assertFalse(first.sameEnvironment(second), "two expired references cannot establish the same worker")
    }

    @Test
    fun `a reopen inside final strategy validation retires the indexed answer`(@TempDir root: Path): Unit =
        runBlocking {
            val uri = root.resolve("Subject.groovy").toUri()
            val source = "class Subject {}"
            val documents = DocumentProvider().apply { put(uri, source) }
            val reopen = AtomicBoolean()
            val manager = WorkspaceManager().apply {
                setTestClasspath(root) {
                    if (reopen.compareAndSet(true, false)) documents.put(uri, source)
                    emptyList()
                }
            }
            val compilation = GroovyCompilationService(documentProvider = documents, workspaceManager = manager)
            try {
                compilation.compile(uri, source)
                val result = compilation.referenceReadiness(uri).query {
                    reopen.set(true)
                    emptyList<String>()
                }
                assertFalse(reopen.get(), "the final strategy lookup must exercise the reopen")
                assertIs<SnapshotQuery.Stale>(result)
            } finally {
                compilation.shutdown()
            }
        }

    @Test
    fun `two current environments cannot share one unqualified symbol identity`(@TempDir root: Path): Unit =
        runBlocking {
            val left = root.resolve("left.groovy").toUri()
            val right = root.resolve("right.groovy").toUri()
            val leftPath = Files.createDirectory(root.resolve("left-classes"))
            val rightPath = Files.createDirectory(root.resolve("right-classes"))
            val strategy = object : ProjectStrategy {
                override val id = "per-document"
                override val displayName = "Per-document classpath"
                override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
                override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
                override fun overridesClasspath(uri: URI) = true
                override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>) =
                    listOf(if (uri == left) leftPath else rightPath)
            }
            val registry = ProjectStrategyRegistry(listOf(strategy))
            registry.selectStrategies(root, ServerConfiguration())
            val manager = WorkspaceManager().apply { setStrategyRegistry(registry) }
            val documents = DocumentProvider()
            val inventory = documents.captureDocuments()
            val compilation = GroovyCompilationService(documentProvider = documents, workspaceManager = manager)
            val leftText = "package example; class Api { def target() { 1 } }"
            val rightText = "package example; class Api { def target() { 2 } }"
            try {
                // Both closed files compile independently without exposing duplicate classes as open overlays.
                Files.writeString(Path.of(left), leftText)
                Files.writeString(Path.of(right), rightText)
                assertNotNull(compilation.ensureCompiled(left))
                assertNotNull(compilation.ensureCompiled(right))
                assertSame(inventory, documents.captureDocuments())
                val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
                val leftTarget = assertNotNull(index.getDocument(left)).symbols.single { it.name == "target" }
                val rightTarget = assertNotNull(index.getDocument(right)).symbols.single { it.name == "target" }
                assertEquals(leftTarget.symbol, rightTarget.symbol)
                val leftContext = manager.compileContextFor(left, leftText)
                val rightContext = manager.compileContextFor(right, rightText)
                assertTrue(leftContext.classpath != rightContext.classpath)
                for (uri in listOf(left, right)) {
                    val acquired = compilation.acquireSnapshot(uri, mode = ParseMode.MINIMAL)
                    val held = assertIs<SnapshotAcquisition.Ready>(acquired)
                    assertTrue(held.isCurrent())
                    assertTrue(assertNotNull(held.snapshot.referenceProducer).complete)
                }
                val result = compilation.referenceReadiness(left).query { error("Mixed classpaths reached lookup") }
                val failure = assertIs<SnapshotQuery.Failure>(result)
                val response = assertIs<ResponseErrorException>(failure.cause).responseError
                assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
                // This check runs only after both original publications pass their individual input checks.
                assertTrue(response.message.startsWith("Reference index combines different compiler environments at "))
            } finally {
                compilation.shutdown()
            }
        }
}
