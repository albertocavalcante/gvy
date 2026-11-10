package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.ConfigurationFingerprint
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A watcher may report a source under another spelling than the listed one, the spelling its cache entry, fence
 * and index are keyed by: through a symlinked root's real path, or in another case under a case-insensitive root
 * (#1440). Retirement, indexing and removal must act on the listed spelling. Case is simulated with an injected
 * probe so the tests are deterministic on case-sensitive Linux.
 */
class WatchedSpellingRetireTest {
    @TempDir
    lateinit var tempDir: Path

    private val base: Path by lazy { tempDir.toRealPath() }
    private val workspaceRoot: Path by lazy { Files.createDirectories(base.resolve("ws")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = DocumentProvider()
    private val paused = ArrayDeque<Runnable>()
    private lateinit var compilation: GroovyCompilationService
    private lateinit var workspace: GroovyWorkspaceService

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun start(caseInsensitive: Boolean = false, normalisesNames: Boolean = false) {
        val manager = WorkspaceManager(ConfigurationFingerprint::compute, { caseInsensitive }, normalisesNames)
        compilation = spyk(
            GroovyCompilationService(
                ioDispatcher = Dispatchers.Unconfined,
                documentProvider = documents,
                workspaceManager = manager,
            ),
        )
        workspace = GroovyWorkspaceService(
            compilation,
            compilation.symbolIndexingService,
            scope,
            sourceChangeDispatcher = Executor { paused.addLast(it) }.asCoroutineDispatcher(),
        )
    }

    private fun write(path: Path, text: String): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    /** `ws/src`, the configured root, links to `ws/real/src`: the listed spelling is the link's. */
    private fun linkedRoot(vararg files: String): Pair<Path, Path> {
        val real = Files.createDirectories(workspaceRoot.resolve("real/src"))
        files.forEach { write(real.resolve(it), "class ${it.removeSuffix(".groovy")} { int v = 1 }") }
        val link = Files.createSymbolicLink(workspaceRoot.resolve("src"), real)
        compilation.updateWorkspaceModel(workspaceRoot, emptyList(), listOf(link))
        return link to real
    }

    private fun notify(vararg events: Pair<Path, FileChangeType>) = workspace.didChangeWatchedFiles(
        DidChangeWatchedFilesParams(events.map { (path, type) -> FileEvent(path.toUri().toString(), type) }),
    )

    private fun drain() {
        while (paused.isNotEmpty()) paused.removeFirst().run()
    }

    @Test
    fun `a change reported by the real path of a linked root retires the listed parse at once`(): Unit = runBlocking {
        start()
        val (link, real) = linkedRoot("A.groovy")
        val listed = link.resolve("A.groovy").toUri()
        assertEquals(listOf(link.resolve("A.groovy")), compilation.workspaceManager.getWorkspaceSources())
        assertNotNull(compilation.ensureCompiled(listed))
        write(real.resolve("A.groovy"), "class A { int v = 2 }")

        notify(real.resolve("A.groovy") to FileChangeType.Changed)

        assertNull(compilation.getCompiledSnapshot(listed), "the listed parse is served while the queue waits")
        assertEquals("class A { int v = 2 }", compilation.ensureCompiled(listed)?.sourceText)
    }

    @Test
    fun `a change reported in another case on a case-insensitive root retires the listed parse`(): Unit = runBlocking {
        start(caseInsensitive = true)
        val src = Files.createDirectories(workspaceRoot.resolve("src"))
        val file = write(src.resolve("A.groovy"), "class A { int v = 1 }")
        compilation.updateWorkspaceModel(workspaceRoot, emptyList(), listOf(src))
        assertNotNull(compilation.ensureCompiled(file.toUri()))
        write(file, "class A { int v = 2 }")

        // On a case-insensitive disk `a.groovy` is the same file; on Linux it need not exist for the event.
        notify(src.resolve("a.groovy") to FileChangeType.Changed)

        assertNull(compilation.getCompiledSnapshot(file.toUri()), "the listed parse is served")
    }

    @Test
    fun `files created and changed through the real path are indexed under the listed spelling only`(): Unit =
        runBlocking {
            start()
            val (link, real) = linkedRoot("A.groovy")
            write(real.resolve("A.groovy"), "class A { int v = 2 }")
            write(real.resolve("B.groovy"), "class B {}")

            notify(
                real.resolve("A.groovy") to FileChangeType.Changed,
                real.resolve("B.groovy") to FileChangeType.Created,
            )
            drain()

            listOf("A.groovy", "B.groovy").forEach { name ->
                assertNotNull(compilation.getSymbolStorage(link.resolve(name).toUri()), "$name listed spelling")
                assertNull(compilation.getSymbolStorage(real.resolve(name).toUri()), "$name indexed twice")
            }
            coVerify(exactly = 0) {
                compilation.refreshSemanticFromDisk(match { uris -> uris.any { it.path.contains("/real/") } })
            }
        }

    @Test
    fun `a file deleted through the real path leaves the cache and the index under the listed spelling`(): Unit =
        runBlocking {
            start()
            val (link, real) = linkedRoot("A.groovy")
            val listed = link.resolve("A.groovy").toUri()
            assertNotNull(compilation.ensureCompiled(listed))
            compilation.indexAllWorkspaceSources(listOf(listed))
            assertNotNull(compilation.getSymbolStorage(listed), "precondition")

            Files.delete(real.resolve("A.groovy"))
            notify(real.resolve("A.groovy") to FileChangeType.Deleted)
            drain()

            assertEquals(emptyList(), compilation.workspaceManager.getWorkspaceSources())
            assertNull(compilation.getCompiledSnapshot(listed), "cache")
            assertNull(compilation.getSymbolStorage(listed), "index")
        }

    @Test
    fun `a buffer open under the event spelling keeps its parse and the closed listed spelling is retired`(): Unit =
        runBlocking {
            start()
            val (link, real) = linkedRoot("A.groovy")
            val listed = link.resolve("A.groovy").toUri()
            val opened = real.resolve("A.groovy").toUri()
            assertNotNull(compilation.ensureCompiled(listed))
            documents.put(opened, "class A { int v = 3 }")
            compilation.compile(opened, "class A { int v = 3 }")
            val buffer = assertNotNull(compilation.getParseResult(opened))
            // Saved from the buffer: the listed spelling's closed parse no longer describes the disk.
            write(real.resolve("A.groovy"), "class A { int v = 3 }")

            notify(real.resolve("A.groovy") to FileChangeType.Changed)
            assertNull(compilation.getCompiledSnapshot(listed), "the closed listed parse is still served")
            drain()

            assertSame(buffer, compilation.getParseResult(opened), "the open buffer was retired")
            assertNotNull(compilation.getSymbolStorage(listed), "the listed spelling is re-indexed from the disk")
        }

    @Test
    fun `a buffer open under the listed spelling keeps its parse and the closed event spelling is retired`(): Unit =
        runBlocking {
            start()
            val (link, real) = linkedRoot("A.groovy")
            val listed = link.resolve("A.groovy").toUri()
            val reported = real.resolve("A.groovy").toUri()
            assertNotNull(compilation.ensureCompiled(reported))
            documents.put(listed, "class A { int v = 3 }")
            compilation.compile(listed, "class A { int v = 3 }")
            val buffer = assertNotNull(compilation.getParseResult(listed))

            notify(real.resolve("A.groovy") to FileChangeType.Changed)
            assertNull(compilation.getCompiledSnapshot(reported), "the closed event-spelled parse is still served")
            drain()

            assertSame(buffer, compilation.getParseResult(listed), "the open buffer was retired")
            assertNull(compilation.getSymbolStorage(reported), "the event spelling was indexed")
        }

    @Test
    fun `a change reported through an alias root retires the listed parse`(): Unit = runBlocking {
        start()
        val src = Files.createDirectories(workspaceRoot.resolve("src"))
        val alias = Files.createSymbolicLink(workspaceRoot.resolve("alias"), src)
        val file = write(src.resolve("A.groovy"), "class A { int v = 1 }")
        compilation.updateWorkspaceModel(workspaceRoot, emptyList(), listOf(src, alias))
        assertEquals(listOf(file), compilation.workspaceManager.getWorkspaceSources())
        assertNotNull(compilation.ensureCompiled(file.toUri()))
        write(file, "class A { int v = 2 }")

        notify(alias.resolve("A.groovy") to FileChangeType.Changed)

        assertNull(compilation.getCompiledSnapshot(file.toUri()), "the listed parse is served")
    }

    @Test
    fun `a change reported in another Unicode normalisation retires the listed parse`(): Unit = runBlocking {
        // APFS ignores normalisation: the scan may hold NFD while the client reports NFC.
        start(normalisesNames = true)
        val src = Files.createDirectories(workspaceRoot.resolve("src"))
        val file = write(src.resolve("Cafe\u0301.groovy"), "class Cafe { int v = 1 }")
        compilation.updateWorkspaceModel(workspaceRoot, emptyList(), listOf(src))
        assertNotNull(compilation.ensureCompiled(file.toUri()))
        write(file, "class Cafe { int v = 2 }")

        notify(src.resolve("Caf\u00e9.groovy") to FileChangeType.Changed)

        assertNull(compilation.getCompiledSnapshot(file.toUri()), "the listed parse is served")
    }
}
