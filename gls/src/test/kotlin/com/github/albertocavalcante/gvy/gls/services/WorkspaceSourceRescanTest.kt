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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Files created or deleted after startup must reach the workspace source list the compile uses (#1422). */
class WorkspaceSourceRescanTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val compilation = spyk(GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined))
    private val workspace = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, scope)

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
}
