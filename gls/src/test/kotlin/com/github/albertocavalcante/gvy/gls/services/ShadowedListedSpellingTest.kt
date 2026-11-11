package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.ConfigurationFingerprint
import com.github.albertocavalcante.gvy.gls.compilation.DocumentTargetSelector
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client can open a listed source under another spelling than the listed one: a symlinked root's real path,
 * or another case on a case-insensitive file system (#1446). While that buffer is open it stands for the file, so
 * the listed copy must not compile next to it, nor answer workspace symbols, references or implementations a
 * second time; once it closes, the listed copy stands for the file again, once. Case is simulated with an
 * injected probe so the tests are deterministic on case-sensitive Linux.
 */
class ShadowedListedSpellingTest {
    @TempDir
    lateinit var tempDir: Path

    private val base: Path by lazy { tempDir.toRealPath() }
    private val workspaceRoot: Path by lazy { Files.createDirectories(base.resolve("ws")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val documents = DocumentProvider()
    private lateinit var compilation: GroovyCompilationService
    private lateinit var textDocuments: GroovyTextDocumentService
    private lateinit var workspace: GroovyWorkspaceService
    private var reads = 0

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun start(caseInsensitive: Boolean = false) {
        compilation = GroovyCompilationService(
            ioDispatcher = Dispatchers.Unconfined,
            documentProvider = documents,
            workspaceManager = WorkspaceManager(
                ConfigurationFingerprint::compute,
                { caseInsensitive },
                normalisesNames = false,
                canonicalise = { path ->
                    reads++
                    DocumentTargetSelector.canonical(path)
                },
            ),
        )
        val defaults = ServerConfiguration()
        textDocuments = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(
                documentProvider = documents,
                serverConfiguration = defaults.copy(
                    diagnosticConfig = defaults.diagnosticConfig.copy(
                        disabledProviders = setOf("codenarc", "custom-rules", "unused-imports"),
                    ),
                ),
                client = { mockk<LanguageClient>(relaxed = true) },
            ),
        )
        workspace = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, scope)
    }

    /** `ws/src`, the configured root, links to `ws/real/src` holding `X.groovy`: the listed spelling is the link's. */
    private fun linkedRoot(): Pair<URI, URI> {
        val real = Files.createDirectories(workspaceRoot.resolve("real/src"))
        Files.writeString(real.resolve("X.groovy"), TEXT)
        val link = Files.createSymbolicLink(workspaceRoot.resolve("src"), real)
        compilation.updateWorkspaceModel(workspaceRoot, emptyList(), listOf(link))
        assertEquals(listOf(link.resolve("X.groovy")), compilation.workspaceManager.getWorkspaceSources())
        return link.resolve("X.groovy").toUri() to real.resolve("X.groovy").toUri()
    }

    private fun open(uri: URI, text: String = TEXT) =
        textDocuments.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri.toString(), "groovy", 1, text)))

    private fun close(uri: URI) = textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier("$uri")))

    private fun symbolUris(query: String): List<String> =
        workspace.symbol(WorkspaceSymbolParams(query)).get().left.map { it.location.uri }

    @Test
    fun `a buffer open under the real path compiles without its listed copy`(): Unit = runBlocking {
        start()
        val (_, opened) = linkedRoot()

        open(opened)
        val result = assertNotNull(compilation.compile(opened, TEXT))

        val messages = result.diagnostics.map { it.message }
        assertTrue(messages.none { "duplicate" in it.lowercase() }, "the listed copy compiled beside it: $messages")
    }

    @Test
    fun `workspace symbols list a class once, under the open buffer's spelling`(): Unit = runBlocking {
        start()
        val (listed, opened) = linkedRoot()
        compilation.indexAllWorkspaceSources(listOf(listed))

        open(opened)

        assertEquals(listOf(opened.toString()), symbolUris("Shadowed").distinct(), "class listed under both spellings")
    }

    @Test
    fun `once the buffer closes the class is listed once, under the listed spelling`(): Unit = runBlocking {
        start()
        val (listed, opened) = linkedRoot()
        compilation.indexAllWorkspaceSources(listOf(listed))
        open(opened)

        close(opened)

        assertEquals(listOf(listed.toString()), symbolUris("Shadowed").distinct(), "the closed spelling stays indexed")
    }

    @Test
    fun `a buffer open in another case shadows the listed copy on a case-insensitive root`(): Unit = runBlocking {
        start(caseInsensitive = true)
        val src = Files.createDirectories(workspaceRoot.resolve("src"))
        val listed = Files.writeString(src.resolve("X.groovy"), TEXT).toUri()
        compilation.updateWorkspaceModel(workspaceRoot, emptyList(), listOf(src))
        compilation.indexAllWorkspaceSources(listOf(listed))
        // On a case-insensitive disk `x.groovy` is the same file; on Linux the buffer needs no file.
        val opened = src.resolve("x.groovy").toUri()

        open(opened)

        assertEquals(listOf(opened.toString()), symbolUris("Shadowed").distinct(), "class listed under both spellings")
    }

    @Test
    fun `references and implementations answer once, from the open buffer`(): Unit = runBlocking {
        start()
        val (listed, opened) = linkedRoot()
        assertNotNull(compilation.compile(listed, TEXT)) // publishes the listed copy's semantic document
        val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
        assertNotNull(index.getDocument(listed), "precondition: the listed copy has semantic facts")

        open(opened)
        val document = assertNotNull(index.getDocument(opened), "precondition: the buffer has semantic facts")
        val symbol = document.symbols.first { it.kind == SymbolKind.CLASS }.symbol

        assertEquals(setOf(opened), index.findReferenceDocuments(symbol).keys, "references from both spellings")
        assertNotNull(index.findImplementationDocument(symbol), "two definitions hide the implementation document")
    }

    @Test
    fun `buffers open under both spellings both stand`(): Unit = runBlocking {
        start()
        val (listed, opened) = linkedRoot()

        open(listed)
        open(opened)

        assertEquals(
            setOf(listed.toString(), opened.toString()),
            symbolUris("Shadowed").toSet(),
            "an open buffer is never shadowed",
        )
    }

    @Test
    fun `workspace requests with a shadowing buffer open read no real paths after it opens`(): Unit = runBlocking {
        start()
        // The root is configured by its real path and the buffer opened through a link to it: translating that
        // spelling needs a real-path read, which belongs to the open, not to every request after it (#1316).
        val real = Files.createDirectories(workspaceRoot.resolve("real/src"))
        Files.writeString(real.resolve("X.groovy"), TEXT)
        val link = Files.createSymbolicLink(workspaceRoot.resolve("src"), real)
        compilation.updateWorkspaceModel(workspaceRoot, emptyList(), listOf(real))
        val listed = real.resolve("X.groovy").toUri()
        val opened = link.resolve("X.groovy").toUri()
        compilation.indexAllWorkspaceSources(listOf(listed))
        open(opened)

        reads = 0
        repeat(REQUESTS) {
            assertEquals(listOf(opened.toString()), symbolUris("Shadowed").distinct(), "listed under both spellings")
        }
        assertNotNull(compilation.compile(opened, TEXT))
        assertEquals(0, reads, "real-path reads on the request path")

        // A model that changes no root or listing (a dependency or target re-stamp) keeps every translation.
        assertTrue(compilation.workspaceManager.updateDependencies(listOf(workspaceRoot.resolve("lib.jar"))))
        repeat(REQUESTS) { symbolUris("Shadowed") }
        assertEquals(0, reads, "real-path reads after a model change that keeps the roots and listing")

        // A listing change is applied on the source queue, which spells the open buffers again there.
        val added = Files.writeString(real.resolve("Y.groovy"), "class Added {}\n")
        assertTrue(compilation.onWorkspaceSourcesChanged(listOf(added), emptyList()).changed)
        reads = 0
        // The change cleared the caches; the buffer's next compile republishes it, shadowing as before.
        assertNotNull(compilation.compile(opened, TEXT))
        symbolUris("Shadowed")
        assertEquals(0, reads, "real-path reads on the first requests after a listing change")
    }

    @Test
    fun `a buffer opened before the workspace initialises shadows once the roots are discovered`(): Unit = runBlocking {
        start(caseInsensitive = true)
        // A Jenkins-style workspace: its bare `src` is discovered as the root when the workspace initialises.
        val src = Files.createDirectories(workspaceRoot.resolve("src"))
        val listed = Files.writeString(src.resolve("X.groovy"), TEXT).toUri()
        val opened = src.resolve("x.groovy").toUri()
        open(opened) // before any root is known: nothing to shadow yet

        startUp()
        compilation.indexAllWorkspaceSources(listOf(listed))
        reads = 0

        assertEquals(listOf(opened.toString()), symbolUris("Shadowed").distinct(), "listed under both spellings")
        assertEquals(0, reads, "real-path reads on the first request after the workspace initialised")
    }

    /**
     * Runs the startup path up to dependency resolution, which never runs: its scope is cancelled, so the model
     * published by resolution cannot refresh the spellings in the startup model's place.
     */
    private fun startUp() {
        val cancelled = CoroutineScope(SupervisorJob()).apply { cancel() }
        val startup = ProjectStartupManager(compilation, emptyList(), cancelled, ProjectStrategyRegistry())
        val params = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(workspaceRoot.toUri().toString(), "ws"))
        }
        startup.startAsyncDependencyResolution(null, params, null, {})
        assertEquals(listOf(workspaceRoot.resolve("src")), compilation.workspaceManager.getSourceRoots(), "startup")
    }

    private companion object {
        const val REQUESTS = 20
        const val TEXT = "class Shadowed { int value }\n"
    }
}
