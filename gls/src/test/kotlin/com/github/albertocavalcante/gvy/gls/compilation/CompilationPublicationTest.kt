// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class CompilationPublicationTest {
    @TempDir lateinit var workspace: Path

    private inner class Fixture(
        dispatcher: CoroutineDispatcher,
        invalidateDuringParse: Boolean,
        emitAst: Boolean = true,
    ) {
        val path = workspace.resolve("Example.groovy")
        val uri = path.toUri()
        val content = "class Example {}"
        val index = WorkspaceSymbolIndex(GroovySemanticDB())
        val indexer = SemanticIndexer(index)
        val orchestrator: CompilationOrchestrator

        init {
            Files.writeString(path, content)
            val manager = WorkspaceManager().also { it.initializeWorkspace(workspace) }
            val parser = GroovyParserFacade()
            val worker = mockk<WorkerSessionManager>()
            every { worker.parse(any()) } answers {
                parser.parse(firstArg())
                    .let { if (emitAst) it else it.copy(ast = null) }
                    .also { if (invalidateDuringParse) indexer.remove(uri) }
            }
            orchestrator = CompilationOrchestrator(
                CompilationOrchestratorDependencies(
                    cacheService = CompilationCacheService(),
                    workerSessionManager = worker,
                    workspaceManager = manager,
                    symbolIndexer = mockk(relaxed = true),
                    parseAccessor = mockk(relaxed = true),
                    resultMapper = CompilationResultMapper(),
                    ioDispatcher = dispatcher,
                    errorHandler = CompilationErrorHandler(),
                    semanticIndexer = indexer,
                ),
            )
        }
    }

    @Test
    fun `synchronous compile cannot publish after invalidation during parse`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), invalidateDuringParse = true)

        fixture.orchestrator.compile(fixture.uri, fixture.content)

        assertNull(fixture.index.getDocument(fixture.uri))
    }

    @Test
    fun `on demand disk compile cannot publish after invalidation during parse`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), invalidateDuringParse = true)

        assertNotNull(fixture.orchestrator.ensureCompiled(fixture.uri))

        assertNull(fixture.index.getDocument(fixture.uri))
    }

    @Test
    fun `queued compile cannot acquire a fresh lifecycle after invalidation`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), invalidateDuringParse = false)
        val queued = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        fixture.indexer.remove(fixture.uri)

        queued.await()

        assertNull(fixture.index.getDocument(fixture.uri))
    }

    @Test
    fun `cancelling queued compile releases its pending publication`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), invalidateDuringParse = false)
        val observer = fixture.indexer.begin(fixture.uri)
        val queued = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        observer.close()

        queued.cancel()
        queued.join()

        fixture.indexer.begin(fixture.uri).use { fresh -> assertNotSame(observer.identity, fresh.identity) }
        assertNull(fixture.index.getDocument(fixture.uri))
    }

    @Test
    fun `sharing queued compilation releases the unused publication token`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), invalidateDuringParse = false)
        val observer = fixture.indexer.begin(fixture.uri)
        val queued = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        val shared = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        assertSame(queued, shared)
        observer.close()

        queued.cancel()
        queued.join()

        fixture.indexer.begin(fixture.uri).use { fresh -> assertNotSame(observer.identity, fresh.identity) }
    }

    @Test
    fun `completed compilation without an AST releases its publication token`(): Unit = runTest {
        val fixture = Fixture(StandardTestDispatcher(testScheduler), invalidateDuringParse = false, emitAst = false)
        val observer = fixture.indexer.begin(fixture.uri)
        val queued = fixture.orchestrator.compileAsync(this, fixture.uri, fixture.content)
        observer.close()

        queued.await()

        fixture.indexer.begin(fixture.uri).use { fresh -> assertNotSame(observer.identity, fresh.identity) }
        assertNull(fixture.index.getDocument(fixture.uri))
    }

    @Test
    fun `already cancelled creator releases its publication token`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fixture = Fixture(dispatcher, invalidateDuringParse = false)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        owner.cancel()
        val observer = fixture.indexer.begin(fixture.uri)
        val abandoned = fixture.orchestrator.compileAsync(owner, fixture.uri, fixture.content)
        observer.close()

        abandoned.join()

        fixture.indexer.begin(fixture.uri).use { fresh -> assertNotSame(observer.identity, fresh.identity) }
        assertNull(fixture.index.getDocument(fixture.uri))
    }
}
