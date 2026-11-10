package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Files created or deleted after startup must reach the workspace source list the compile uses (#1422). */
class WorkspaceSourceRescanTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val compilation = spyk(GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined))
    private val workspace = GroovyWorkspaceService(
        compilation,
        compilation.symbolIndexingService,
        scope,
        sourceChangeDispatcher = Dispatchers.Unconfined,
    )

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun jarOf(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())

    private fun src(): Path {
        val src = Files.createDirectories(tempDir.resolve("src"))
        compilation.updateWorkspaceModel(
            tempDir,
            listOf(jarOf(spock.lang.Specification::class.java), jarOf(groovy.lang.GroovyObject::class.java)),
            listOf(src),
        )
        return src
    }

    private fun notify(vararg events: Pair<Path, FileChangeType>) = workspace.didChangeWatchedFiles(
        DidChangeWatchedFilesParams(events.map { (path, type) -> FileEvent(path.toUri().toString(), type) }),
    )

    private fun messages(file: Path): List<String> =
        compilation.getCompiledSnapshot(file.toUri())?.parseResult?.diagnostics?.map { it.message }.orEmpty()

    @Test
    fun `a file created after startup joins the compile through the watched-file event`(): Unit = runBlocking {
        val src = src()
        val spec = src.resolve("TableSpec.groovy")
        val text = "class TableSpec extends Outer2.Inner {}\n"
        Files.writeString(spec, text)
        compilation.compile(spec.toUri(), text)
        assertTrue(messages(spec).any { it.contains("unable to resolve class Outer2.Inner") }, "precondition")

        val outers = src.resolve("Outers.groovy")
        Files.writeString(
            outers,
            "import spock.lang.*\n" +
                "class Outer1 { static abstract class Inner extends Specification {} }\n" +
                "class Outer2 { static abstract class Inner extends Specification {} }\n",
        )
        notify(outers to FileChangeType.Created)
        compilation.compile(spec.toUri(), text)

        assertTrue(compilation.workspaceManager.getWorkspaceSources().contains(outers), "source list")
        assertTrue(messages(spec).none { it.contains("unable to resolve class Outer2.Inner") }, "${messages(spec)}")
    }

    @Test
    fun `one notification with several created files clears the caches once`() {
        val src = src()
        val files = (1..5).map { index ->
            src.resolve("Created$index.groovy").also { Files.writeString(it, "class C$index {}\n") }
        }
        clearMocks(compilation, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)

        notify(*files.map { it to FileChangeType.Created }.toTypedArray())

        verify(exactly = 1) { compilation.clearCaches() }
        assertTrue(compilation.workspaceManager.getWorkspaceSources().containsAll(files))
    }

    @Test
    fun `a replace-on-save reported as deleted and created keeps the file listed`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val saved = src.resolve("Saved.groovy").also { Files.writeString(it, "class Saved {}\n") }
        src()
        assertTrue(compilation.workspaceManager.getWorkspaceSources().contains(saved), "precondition")

        notify(saved to FileChangeType.Deleted, saved to FileChangeType.Created)

        assertTrue(compilation.workspaceManager.getWorkspaceSources().contains(saved), "the file exists, so it stays")
    }

    @Test
    fun `a replace reported as created then deleted drops a file that is gone`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val gone = src.resolve("Gone.groovy").also { Files.writeString(it, "class Gone {}\n") }
        src()
        assertTrue(compilation.workspaceManager.getWorkspaceSources().contains(gone), "precondition")

        Files.delete(gone)
        notify(gone to FileChangeType.Created, gone to FileChangeType.Deleted)

        assertFalse(compilation.workspaceManager.getWorkspaceSources().contains(gone), "the file is gone, so it leaves")
    }

    @Test
    fun `a closed-file compile uses a model read after it claims its ticket`(): Unit = runBlocking {
        // Interleave deterministically: the on-demand DISK compile reads the model for its cache lookups, then a
        // created file is published and the caches cleared, and only then does the compile claim and run.
        val armed = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<ParseRequest>()
        lateinit var service: GroovyCompilationService
        val src = Files.createDirectories(tempDir.resolve("src"))
        val outers = src.resolve("Outers.groovy")
        val manager = spyk(WorkspaceManager())
        every { manager.current() } answers {
            val model = callOriginal()
            if (armed.compareAndSet(true, false)) {
                Files.writeString(outers, "class Outers {}\n")
                service.onWorkspaceSourcesChanged(listOf(outers), emptyList())
            }
            model
        }
        service = GroovyCompilationService(
            ioDispatcher = Dispatchers.Unconfined,
            workerSessionDecorator = { session ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult = session.parse(request).also {
                        requests += request
                    }
                }
            },
            workspaceManager = manager,
        )
        service.updateWorkspaceModel(tempDir, emptyList(), listOf(src))
        val closed = src.resolve("Closed.groovy").also { Files.writeString(it, "class Closed {}\n") }

        armed.set(true)
        service.ensureCompiled(closed.toUri())

        val compiled = assertNotNull(requests.lastOrNull { it.uri == closed.toUri() }, "compiled from disk")
        assertTrue(compiled.workspaceSources.contains(outers), "the compile must see the published list: $compiled")
    }

    @Test
    fun `a deleted source leaves the workspace source list`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val gone = src.resolve("Gone.groovy").also { Files.writeString(it, "class Gone {}\n") }
        src()
        assertTrue(compilation.workspaceManager.getWorkspaceSources().contains(gone), "precondition")

        Files.delete(gone)
        notify(gone to FileChangeType.Deleted)

        assertFalse(compilation.workspaceManager.getWorkspaceSources().contains(gone))
    }

    private fun sources() = compilation.workspaceManager.getWorkspaceSources()

    private fun write(path: Path, text: String): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    @Test
    fun `a folder created with sources in it adds them all with one cache clear`() {
        val src = src()
        val pkg = src.resolve("pkg")
        val nested = listOf(
            write(pkg.resolve("A.groovy"), "class A {}\n"),
            write(pkg.resolve("deep/B.groovy"), "class B {}\n"),
        )
        write(pkg.resolve("notes.txt"), "not a source\n")
        clearMocks(compilation, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)

        // A folder copied or moved in is one Created event for the folder: no glob names its files.
        notify(pkg to FileChangeType.Created)

        assertTrue(sources().containsAll(nested), "${sources()}")
        assertEquals(2, sources().size, "${sources()}")
        verify(exactly = 1) { compilation.clearCaches() }
    }

    @Test
    fun `a folder created outside every source root is not scanned`() {
        src()
        val outside = tempDir.resolve("scripts")
        val stray = write(outside.resolve("Stray.groovy"), "class Stray {}\n")

        notify(outside to FileChangeType.Created)

        assertFalse(sources().contains(stray), "${sources()}")
    }

    @Test
    fun `a deleted folder removes only the sources under it`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val under = write(src.resolve("pkg/A.groovy"), "class A {}\n")
        val deeper = write(src.resolve("pkg/deep/B.groovy"), "class B {}\n")
        // Same name prefix, different folder: a string prefix match would wrongly drop it.
        val sibling = write(src.resolve("pkgUtil/C.groovy"), "class C {}\n")
        src()
        assertTrue(sources().containsAll(listOf(under, deeper, sibling)), "precondition")

        src.resolve("pkg").toFile().deleteRecursively()
        notify(src.resolve("pkg") to FileChangeType.Deleted)

        assertEquals(listOf(sibling), sources())
    }

    @Test
    fun `a deleted Jenkinsfile is not mistaken for a folder`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val jenkinsfile = write(src.resolve("Jenkinsfile"), "pipeline {}\n")
        val steps = write(src.resolve("JenkinsfileSteps.groovy"), "class JenkinsfileSteps {}\n")
        val named = write(src.resolve("Jenkinsfile.groovy"), "class Jenkinsfile {}\n")
        val other = write(src.resolve("lib/Lib.groovy"), "class Lib {}\n")
        src()
        val before = sources()
        assertTrue(before.containsAll(listOf(steps, named, other)), "precondition")
        clearMocks(compilation, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)

        Files.delete(jenkinsfile)
        notify(jenkinsfile to FileChangeType.Deleted)

        assertEquals(before, sources(), "nothing lives under a file, so nothing leaves")
        verify(exactly = 0) { compilation.clearCaches() }
    }

    @Test
    fun `a moved folder leaves its old path and joins under the new one`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        write(src.resolve("old/A.groovy"), "class A {}\n")
        src()
        val moved = Files.move(src.resolve("old"), src.resolve("new"))
        clearMocks(compilation, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)

        notify(src.resolve("old") to FileChangeType.Deleted, moved to FileChangeType.Created)

        assertEquals(listOf(moved.resolve("A.groovy")), sources())
        verify(exactly = 1) { compilation.clearCaches() }
    }

    @Test
    fun `a folder deleted and recreated in one notification keeps what is on disk`() {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val kept = write(src.resolve("pkg/A.groovy"), "class A {}\n")
        val dropped = write(src.resolve("pkg/B.groovy"), "class B {}\n")
        src()
        Files.delete(dropped)

        notify(src.resolve("pkg") to FileChangeType.Deleted, src.resolve("pkg") to FileChangeType.Created)

        assertEquals(listOf(kept), sources())
    }

    private fun indexed(file: Path) = compilation.symbolIndexingService.getSymbolIndex(file.toUri()) != null

    @Test
    fun `sources found in a created folder are indexed`() {
        val src = src()
        val found = write(src.resolve("pkg/Found.groovy"), "class Found {}\n")
        assertFalse(indexed(found), "precondition")

        notify(src.resolve("pkg") to FileChangeType.Created)

        assertTrue(indexed(found), "a moved-in file must reach workspace symbols, not only the compile")
    }

    @Test
    fun `sources under a deleted folder leave the index`(): Unit = runBlocking {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val gone = write(src.resolve("pkg/Gone.groovy"), "class Gone {}\n")
        src()
        compilation.symbolIndexingService.indexAllWorkspaceSources(listOf(gone.toUri()))
        assertTrue(indexed(gone), "precondition")
        clearMocks(compilation, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)

        src.resolve("pkg").toFile().deleteRecursively()
        notify(src.resolve("pkg") to FileChangeType.Deleted)

        assertFalse(indexed(gone))
        verify(exactly = 1) { compilation.invalidateCache(gone.toUri()) }
    }

    @Test
    fun `a folder named like a source is scanned when created and pruned when deleted`() {
        val src = src()
        val folder = src.resolve("lib.groovy")
        val inner = write(folder.resolve("Inner.groovy"), "class Inner {}\n")

        notify(folder to FileChangeType.Created)
        assertEquals(listOf(inner), sources(), "created")

        folder.toFile().deleteRecursively()
        notify(folder to FileChangeType.Deleted)
        assertEquals(emptyList(), sources(), "deleted")
    }

    @Test
    fun `events under directories the scans skip are dropped before any work`() {
        val src = src()
        val vendored = write(src.resolve("node_modules/pkg/Vendored.groovy"), "class Vendored {}\n")
        clearMocks(compilation, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)

        notify(
            src.resolve("node_modules/pkg") to FileChangeType.Created,
            src.resolve(".git/index") to FileChangeType.Created,
        )

        assertFalse(sources().contains(vendored), "${sources()}")
        verify(exactly = 0) { compilation.onWorkspaceSourcesChanged(any(), any()) }
    }

    @Test
    fun `notifications apply off the caller thread, in arrival order`() {
        val tasks = ArrayDeque<Runnable>()
        val queued = GroovyWorkspaceService(
            compilation,
            compilation.symbolIndexingService,
            scope,
            sourceChangeDispatcher = Executor { tasks.addLast(it) }.asCoroutineDispatcher(),
        )
        val src = src()
        val pkg = src.resolve("pkg")
        write(pkg.resolve("A.groovy"), "class A {}\n")
        val event = { type: FileChangeType ->
            DidChangeWatchedFilesParams(listOf(FileEvent(pkg.toUri().toString(), type)))
        }

        // The folder stays on disk: applied in order the delete wins, reversed the create would.
        queued.didChangeWatchedFiles(event(FileChangeType.Created))
        assertEquals(emptyList(), sources(), "a folder walk must not run on the notification thread")
        queued.didChangeWatchedFiles(event(FileChangeType.Deleted))
        while (tasks.isNotEmpty()) tasks.removeFirst().run()

        assertEquals(emptyList(), sources(), "the delete was applied after the create")
    }
}
