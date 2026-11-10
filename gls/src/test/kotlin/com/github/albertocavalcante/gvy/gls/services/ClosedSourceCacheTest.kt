package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Content-less readers (`getAst`, `getParseResult`, `ensureCompiled`) have no text to compare a cache entry
 * with, so the entry itself must follow the file: once a buffer closes, or a closed file changes on disk,
 * they must not keep serving the old parse (#1184).
 */
class ClosedSourceCacheTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = DocumentProvider()
    private val compilation =
        spyk(GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents))
    private val textDocuments = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(
            documentProvider = documents,
            serverConfiguration = parserOnlyConfiguration(),
            client = { mockk<LanguageClient>(relaxed = true) },
        ),
    )
    private val workspace = GroovyWorkspaceService(
        compilation,
        compilation.symbolIndexingService,
        scope,
        sourceChangeDispatcher = Dispatchers.Unconfined,
    )

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `content-less reads after closing an unsaved buffer follow the disk`(): Unit = runBlocking {
        val file = tempDir.resolve("Closed.groovy")
        Files.writeString(file, "class OnDisk {}")
        val uri = file.toUri()
        textDocuments.didOpen(open(uri.toString(), "class Unsaved {}"))
        compilation.ensureCompiled(uri)
        assertEquals(listOf("Unsaved"), classNames(compilation.getAst(uri)))

        textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))

        assertFalse("Unsaved" in classNames(compilation.getAst(uri)), "closed buffer AST still served")
        val compiled = assertNotNull(compilation.ensureCompiled(uri))
        assertEquals("class OnDisk {}", compiled.sourceText)
        assertEquals(listOf("OnDisk"), classNames(compilation.getAst(uri)))
    }

    @Test
    fun `content-less reads after an external edit of a closed file follow the disk`(): Unit = runBlocking {
        val file = tempDir.resolve("Edited.groovy")
        Files.writeString(file, "class Before {}")
        val uri = file.toUri()
        assertEquals("class Before {}", compilation.ensureCompiled(uri)?.sourceText)

        Files.writeString(file, "class After {}")
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(listOf(FileEvent(uri.toString(), FileChangeType.Changed))),
        )

        assertFalse("Before" in classNames(compilation.getAst(uri)), "externally replaced AST still served")
        assertEquals("class After {}", compilation.ensureCompiled(uri)?.sourceText)
    }

    @Test
    fun `closing an unchanged buffer keeps its parse for navigation`(): Unit = runBlocking {
        val file = tempDir.resolve("Same.groovy")
        Files.writeString(file, "class Same {}")
        val uri = file.toUri()
        textDocuments.didOpen(open(uri.toString(), "class Same {}"))
        compilation.ensureCompiled(uri)
        val parsed = assertNotNull(compilation.getParseResult(uri))

        textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString())))

        assertSame(parsed, compilation.getParseResult(uri), "the buffer matched the disk; no reparse is needed")
    }

    @Test
    fun `a close cleanup that runs after a reopen keeps the reopened buffer's facts`(): Unit = runBlocking {
        // The service's background work runs on one thread that is held until the reopen has compiled.
        val held = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val gated = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            executor.execute { held.await(WAIT_SECONDS, TimeUnit.SECONDS) }
            val service = GroovyTextDocumentService(
                gated,
                compilation,
                GroovyTextDocumentServiceOptions(
                    documentProvider = documents,
                    serverConfiguration = parserOnlyConfiguration(),
                    client = { mockk<LanguageClient>(relaxed = true) },
                ),
            )
            val uri = URI.create("untitled:Scratch")
            service.didOpen(open(uri.toString(), "class Original {}"))
            compilation.compile(uri, "class Original {}")
            service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri.toString()))) // cleanup queued
            service.didOpen(open(uri.toString(), "class Reopened {}"))
            compilation.compile(uri, "class Reopened {}")

            held.countDown()
            executor.submit {}.get(WAIT_SECONDS, TimeUnit.SECONDS) // the queued close cleanup has run

            val index = assertNotNull(compilation.getSymbolStorage(uri), "the old close removed the reopened index")
            assertTrue("Reopened" in index.getSymbols(uri).map { it.name })
            assertNotNull(compilation.getCompiledSnapshot(uri), "the old close retired the reopened parse")
        } finally {
            held.countDown()
            gated.cancel()
            executor.shutdownNow()
        }
    }

    private fun classNames(ast: Any?): List<String> =
        (ast as? ModuleNode)?.classes?.map { it.nameWithoutPackage }.orEmpty()

    private companion object {
        const val WAIT_SECONDS = 10L
    }

    private fun parserOnlyConfiguration(): ServerConfiguration {
        val base = ServerConfiguration()
        return base.copy(
            diagnosticConfig = base.diagnosticConfig.copy(
                disabledProviders = setOf("codenarc", "custom-rules", "unused-imports"),
            ),
        )
    }

    /**
     * Notifies [types] for one closed file through a paused source queue, reads it before the queue runs, then
     * drains the queue and runs [afterDrain].
     */
    private fun assertRetiredBeforeQueueRuns(
        name: String,
        vararg types: FileChangeType,
        afterDrain: (Path) -> Unit = {},
    ): Unit = runBlocking {
        val paused = ArrayDeque<Runnable>()
        val queued = GroovyWorkspaceService(
            compilation,
            compilation.symbolIndexingService,
            scope,
            sourceChangeDispatcher = Executor { paused.addLast(it) }.asCoroutineDispatcher(),
        )
        compilation.updateWorkspaceModel(tempDir, emptyList(), listOf(tempDir))
        val file = tempDir.resolve("$name.groovy")
        Files.writeString(file, "class Before {}")
        val uri = file.toUri()
        assertEquals("class Before {}", compilation.ensureCompiled(uri)?.sourceText)

        Files.writeString(file, "class After {}")
        queued.didChangeWatchedFiles(DidChangeWatchedFilesParams(types.map { FileEvent(uri.toString(), it) }))

        // Nothing queued has run: the retirement itself happened on the notification.
        assertFalse(paused.isEmpty(), "the rest of the work is still queued")
        assertFalse("Before" in classNames(compilation.getAst(uri)), "old parse served while the queue waits")
        assertEquals("class After {}", compilation.ensureCompiled(uri)?.sourceText)

        while (paused.isNotEmpty()) paused.removeFirst().run()
        afterDrain(file)
    }

    @Test
    fun `a changed closed file is retired before its queued work runs`() =
        assertRetiredBeforeQueueRuns("Changed", FileChangeType.Changed)

    @Test
    fun `an atomic save reported as deleted and created is retired before its queued work runs`() =
        assertRetiredBeforeQueueRuns("Atomic", FileChangeType.Deleted, FileChangeType.Created) { file ->
            // Never left retired without a refresh: still listed, and rebuilt from the new disk text.
            assertTrue(compilation.workspaceManager.getWorkspaceSources().contains(file), "still listed")
            coVerify { compilation.refreshSemanticFromDisk(match { file.toUri() in it }) }
            assertEquals("class After {}", compilation.ensureCompiled(file.toUri())?.sourceText)
        }

    private fun open(uri: String, content: String) =
        DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, content))
}
