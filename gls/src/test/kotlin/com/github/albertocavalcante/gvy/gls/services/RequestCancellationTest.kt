package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SemanticIndexer
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceCompiler
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.providers.SignatureHelpProvider
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import com.github.albertocavalcante.gvy.gls.providers.testing.RunTestParams
import com.github.albertocavalcante.gvy.gls.providers.testing.TestRequestDelegate
import com.github.albertocavalcante.gvy.gls.worker.InProcessWorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import com.github.albertocavalcante.groovyparser.ast.types.Position as ParserPosition

/**
 * A request whose work is cancelled must end cancelled, not answer with an empty list, a "not found" error or
 * an internal error (audit H3). Each case raises the [CancellationException] a cancelled compile owner or a
 * cancelled caller's `ensureActive()` would raise, at a point inside the request's broad error handling.
 */
class RequestCancellationTest {
    @TempDir
    lateinit var tempDir: Path

    private val uri = URI.create("file:///Cancelled.groovy")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val documents = DocumentProvider().apply { put(uri, "class Cancelled { def m() { m() } }", 1) }
    private val compilation = spyk(GroovyCompilationService(documentProvider = documents))

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `a cancelled document highlight ends cancelled instead of empty`(): Unit = runBlocking {
        coEvery { compilation.ensureCompiled(uri) } throws CancellationException("compile cancelled")
        val service =
            GroovyTextDocumentService(
                scope,
                compilation,
                GroovyTextDocumentServiceOptions(documentProvider = documents),
            )

        val highlight = service.documentHighlight(
            DocumentHighlightParams(TextDocumentIdentifier(uri.toString()), Position(0, 30)),
        )

        assertFailsWith<CancellationException> { highlight.await() }
    }

    @Test
    fun `a cancelled signature help compile is not swallowed`(): Unit = runBlocking {
        every { compilation.getAst(uri) } returns null
        coEvery { compilation.compile(uri, any()) } throws CancellationException("compile cancelled")
        val provider = SignatureHelpProvider(compilation, documents, mockk(relaxed = true))

        assertFailsWith<CancellationException> { provider.provideSignatureHelp(uri.toString(), Position(0, 30)) }
    }

    @Test
    fun `a cancelled definition lookup is not rewritten as symbol not found`(): Unit = runBlocking {
        val model = mockk<GroovyAstModel>()
        every { model.getNodeAt(any(), any<ParserPosition>()) } throws CancellationException("lookup cancelled")
        val resolver = DefinitionResolver(model, mockk(relaxed = true), compilation)

        assertFailsWith<CancellationException> { resolver.findDefinitionAt(uri, ParserPosition(0, 1)) }
    }

    @Test
    fun `a cancelled run-test request is not reported as an internal error`(): Unit = runBlocking {
        every { compilation.workspaceManager.getWorkspaceRoot() } returns tempDir
        val delegate = TestRequestDelegate(scope, compilation, { throw CancellationException("cancelled") })

        assertFailsWith<CancellationException> { delegate.runTest(RunTestParams(uri.toString(), "Suite")).await() }
    }

    @Test
    fun `a cancelled workspace compile propagates instead of becoming a compile error`(): Unit = runBlocking {
        val file = tempDir.resolve("Workspace.groovy").also { Files.writeString(it, "class Workspace {}") }
        val cancelling = object : WorkerSession {
            private val delegate = InProcessWorkerSession(GroovyParserFacade())
            override fun parse(request: ParseRequest): ParseResult =
                delegate.parse(request.copy(checkCancelled = { throw CancellationException("cancelled") }))
        }
        val workspace = mockk<WorkspaceManager>(relaxed = true)
        every { workspace.getWorkspaceSources() } returns listOf(file)
        val compiler = WorkspaceCompiler(
            WorkerSessionManager(cancelling) { error("no remote workers") },
            workspace,
            SemanticIndexer(WorkspaceSymbolIndex(GroovySemanticDB())),
        )

        assertFailsWith<CancellationException> { compiler.compileWorkspace() }
    }
}
