// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.gls.services.DocumentIncarnation
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SnapshotOwnershipTest {
    private val uri = URI.create("file:///Snapshot.groovy")
    private val source = "class Snapshot {}"

    @Test
    fun `a compile retains the exact parser result with its source`(): Unit = runBlocking {
        val parsed = AtomicReference<ParseResult>()
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also(parsed::set)
            }
        })
        try {
            service.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
            val result = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
            assertSame(parsed.get(), result.snapshot.parseResult)
            assertEquals(source, result.snapshot.sourceText)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `a later compile at a higher phase cannot reuse a lower phase cache entry`(): Unit = runBlocking {
        val phases = mutableListOf<Int>()
        val service = GroovyCompilationService(workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    phases.add(request.compilePhase)
                    return delegate.parse(request)
                }
            }
        })
        try {
            service.compile(uri, source, Phases.CONVERSION)
            service.compile(uri, source, Phases.CANONICALIZATION)
            assertEquals(listOf(Phases.CONVERSION, Phases.CANONICALIZATION), phases)
        } finally {
            service.shutdown()
        }
    }

    enum class ChangedInput { WATCHED_FILE, UNSAVED_COMPANION, STRATEGY_CLASSPATH, REQUEST_CLASSPATH_ONLY }

    @ParameterizedTest
    @EnumSource(ChangedInput::class)
    fun `fallback reuse requires the original compiler inputs`(change: ChangedInput, @TempDir root: Path): Unit =
        runBlocking {
            val calls = AtomicInteger()
            val documents = DocumentProvider()
            val paths = AtomicReference<List<Path>>(emptyList())
            val receivedPaths = AtomicReference<List<Path>>()
            val service = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        calls.incrementAndGet()
                        receivedPaths.set(request.classpath)
                        return delegate.parse(request)
                    }
                }
            })
            try {
                service.workspaceManager.initializeWorkspace(root)
                service.workspaceManager.updateWorkspaceModel(root, emptyList(), listOf(root))
                service.workspaceManager.setTestClasspath(root, paths::get)
                val broken = Files.writeString(root.resolve("Broken.groovy"), "class Broken { void m( }")
                val content = "class Uses { Broken dependency }"
                val target = Files.writeString(root.resolve("Uses.groovy"), content).toUri()
                documents.put(target, content, DocumentVersion.of(1))
                service.compile(target, content, Phases.CANONICALIZATION)
                val first = assertNotNull(service.getCompiledSnapshot(target)).parseResult
                assertEquals(Phases.CANONICALIZATION, first.outcome.requestedPhase)
                assertEquals(Phases.CONVERSION, first.outcome.achievedPhase)
                assertEquals(Fallback.COMPILATION_FAILED, first.outcome.fallback)
                service.compile(target, content, Phases.CANONICALIZATION)
                assertEquals(1, calls.get())
                assertSame(first, assertNotNull(service.getCompiledSnapshot(target)).parseResult)

                changeInput(change, broken, service, documents, paths)
                service.compile(target, content, Phases.CANONICALIZATION)
                val refreshed = assertNotNull(service.getCompiledSnapshot(target)).parseResult
                assertEquals(2, calls.get())
                assertNotSame(first, refreshed)
                assertEquals(paths.get(), receivedPaths.get())
                if (change == ChangedInput.WATCHED_FILE || change == ChangedInput.UNSAVED_COMPANION) {
                    assertEquals(Phases.CANONICALIZATION, refreshed.outcome.achievedPhase)
                }
            } finally {
                service.shutdown()
            }
        }

    // FIXME(#2063): Keep enum branching outside the coroutine; jqwik discovers its local WhenMappings class.
    private fun changeInput(
        change: ChangedInput,
        broken: Path,
        service: GroovyCompilationService,
        documents: DocumentProvider,
        paths: AtomicReference<List<Path>>,
    ) {
        when {
            change == ChangedInput.WATCHED_FILE -> {
                Files.writeString(broken, "class Broken {}")
                service.onClosedSourceChanged(broken.toUri(), keepIfUnchanged = false)
            }
            change == ChangedInput.UNSAVED_COMPANION -> documents.put(broken.toUri(), "class Broken {}")
            else -> {
                paths.set(listOf(Files.createDirectory(broken.resolveSibling("classes"))))
                if (change == ChangedInput.REQUEST_CLASSPATH_ONLY) {
                    // The requester resolves B, then the strategy returns A again when the old producer is checked.
                    val firstLookup = AtomicReference(paths.get())
                    service.workspaceManager.setTestClasspath(broken.parent) { firstLookup.getAndSet(emptyList()) }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a paused request cannot adopt a fallback from a newer input owner`(
        rescanSources: Boolean,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val calls = CopyOnWriteArrayList<List<Path>>()
        val documents = DocumentProvider()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstLookup = AtomicBoolean(true)
        val service = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    calls.add(request.workspaceSources.toList())
                    return delegate.parse(request)
                }
            }
        })
        try {
            val content = "class Target { Broken dependency }"
            val target = Files.writeString(root.resolve("Target.groovy"), content).toUri()
            documents.put(target, content, DocumentVersion.of(1))
            service.workspaceManager.initializeWorkspace(root)
            service.workspaceManager.updateWorkspaceModel(root, emptyList(), listOf(root))
            val before = service.workspaceManager.current()
            service.workspaceManager.setTestClasspath(root) {
                if (firstLookup.getAndSet(false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                emptyList()
            }
            val older = async(Dispatchers.Default) { service.compile(target, content, Phases.CANONICALIZATION) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (rescanSources) {
                val marker = Files.writeString(root.resolve("Added.groovy"), "class Added {}")
                service.updateWorkspaceModel(root, emptyList(), listOf(root))
                assertTrue(marker in service.workspaceManager.current().sources && marker !in before.sources)
            } else {
                service.invalidateCache(root.resolve("Other.groovy").toUri())
                assertSame(before, service.workspaceManager.current())
            }
            val after = service.workspaceManager.current()
            assertEquals(before.fingerprint, after.fingerprint)
            Files.writeString(root.resolve("Broken.groovy"), "class Broken { void m( }")
            service.compile(target, content, Phases.CANONICALIZATION)
            val newer = assertNotNull(service.getCompiledSnapshot(target)).parseResult
            assertEquals(Fallback.COMPILATION_FAILED, newer.outcome.fallback)
            assertEquals(Phases.CONVERSION, newer.outcome.achievedPhase)
            assertEquals(listOf(after.sources), calls.toList())
            release.countDown()
            withTimeout(5_000) { older.await() }
            assertEquals(listOf(after.sources, before.sources), calls.toList())
        } finally {
            release.countDown()
            service.shutdown()
        }
    }

    @Test
    fun `identical document state in different lifecycles cannot share work`() {
        val snapshot = DocumentSnapshot(source, null)
        val first = CompilationIdentity(source, "fingerprint", snapshot, DocumentIncarnation())
        val reopened = CompilationIdentity(source, "fingerprint", snapshot, DocumentIncarnation())

        assertFalse(first.matches(reopened))
    }
}

internal fun WorkspaceManager.setTestClasspath(root: Path, paths: () -> List<Path>) {
    val strategy = object : ProjectStrategy {
        override val id = "snapshot-test"
        override val displayName = "Snapshot test"
        override fun canHandle(workspaceRoot: Path, config: ServerConfiguration) = true
        override suspend fun initialize(workspaceRoot: Path, config: ServerConfiguration): Job? = null
        override fun overridesClasspath(uri: URI) = true
        override fun getClasspathForFile(uri: URI, content: String, projectDependencies: List<Path>) = paths()
    }
    val registry = ProjectStrategyRegistry(listOf(strategy))
    registry.selectStrategies(root, ServerConfiguration())
    setStrategyRegistry(registry)
}
