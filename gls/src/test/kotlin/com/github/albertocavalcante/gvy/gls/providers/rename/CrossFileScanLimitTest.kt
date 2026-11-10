package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The cross-file check never allows a rename it could not finish verifying. */
class CrossFileScanLimitTest {
    @TempDir
    lateinit var tempDir: Path

    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(documentProvider = documents)

    private fun workspace(extraFiles: Int): Pair<java.net.URI, String> = runBlocking {
        val a = "class A {\n    def greet() { 'hi' }\n}\n"
        val src = Files.createDirectories(tempDir.resolve("src"))
        Files.writeString(src.resolve("A.groovy"), a)
        repeat(extraFiles) { Files.writeString(src.resolve("F$it.groovy"), "class F$it {\n    def run() { 1 }\n}\n") }
        service.workspaceManager.initializeWorkspace(tempDir)
        val uri = src.resolve("A.groovy").toUri()
        documents.put(uri, a)
        service.compile(uri, a)
        uri to a
    }

    private fun renameMessage(limits: ScanLimits): String? {
        val (uri, text) = workspace(extraFiles = 3)
        val provider = RenameProvider(service, documents, WorkspaceTextCache(), limits)
        val error = runCatching {
            runBlocking { provider.providePrepareRename(uri.toString(), Position(1, text.lines()[1].indexOf("greet"))) }
        }.exceptionOrNull()
        return (error as? ResponseErrorException)?.also {
            assertEquals(ResponseErrorCode.RequestFailed.value, it.responseError.code)
        }?.responseError?.message
    }

    @Test
    fun `more files than the cap is refused as too large to verify`() {
        val message = assertNotNull(renameMessage(ScanLimits(maxFiles = 2)))
        assertTrue("too large to verify that no other file uses 'greet'" in message, message)
    }

    @Test
    fun `more bytes than the cap is refused as too large to verify`() {
        val message = assertNotNull(renameMessage(ScanLimits(maxChars = 10)))
        assertTrue("too large to verify" in message, message)
    }

    @Test
    fun `more walk entries than the cap is refused as too large to verify`() {
        val message = assertNotNull(renameMessage(ScanLimits(maxWalkEntries = 2)))
        assertTrue("too large to verify that no other file uses 'greet' (scan limit reached)" in message, message)
    }

    @Test
    fun `a workspace within the caps is not refused`() {
        assertEquals(null, renameMessage(ScanLimits(maxFiles = 10, maxChars = 1_000_000)))
    }

    @Test
    fun `the cache re-reads a file only when its stamp changes and is bounded`() {
        val cache = WorkspaceTextCache(maxEntries = 2)
        val files = (0..2).map { tempDir.resolve("f$it.groovy").also { f -> Files.writeString(f, "class F$it {}") } }
        files.forEach { assertIs<DiskText.Text>(cache.read(it, 1_000)) }
        assertEquals(2, cache.size)

        Files.writeString(files[2], "class Changed {}")
        val reread = assertIs<DiskText.Text>(cache.read(files[2], 1_000))
        assertEquals("class Changed {}", reread.text)
        assertIs<DiskText.TooLarge>(cache.read(files[2], 1))
        assertIs<DiskText.Missing>(cache.read(tempDir.resolve("gone.groovy"), 1_000))
    }
}
