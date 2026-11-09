package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
        GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
    private val textDocuments = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(
            documentProvider = documents,
            serverConfiguration = parserOnlyConfiguration(),
            client = { mockk<LanguageClient>(relaxed = true) },
        ),
    )
    private val workspace = GroovyWorkspaceService(compilation, compilation.symbolIndexingService, scope)

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

    private fun classNames(ast: Any?): List<String> =
        (ast as? ModuleNode)?.classes?.map { it.nameWithoutPackage }.orEmpty()

    private fun parserOnlyConfiguration(): ServerConfiguration {
        val base = ServerConfiguration()
        return base.copy(
            diagnosticConfig = base.diagnosticConfig.copy(
                disabledProviders = setOf("codenarc", "custom-rules", "unused-imports"),
            ),
        )
    }

    private fun open(uri: String, content: String) =
        DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, content))
}
