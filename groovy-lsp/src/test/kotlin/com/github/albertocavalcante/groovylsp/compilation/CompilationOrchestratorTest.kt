package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompilationOrchestratorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ensureCompiled on-disk parse does not include workspace sources`() = runBlocking {
        val targetPath = tempDir.resolve("Target.groovy")
        Files.writeString(
            targetPath,
            """
            class Target {
            }
            """.trimIndent(),
        )
        val targetUri = targetPath.toUri()

        val workspaceSource = tempDir.resolve("Workspace.groovy")
        Files.writeString(
            workspaceSource,
            """
            class Workspace {
            }
            """.trimIndent(),
        )

        val requestSlot = slot<ParseRequest>()
        val workerSessionManager = mockk<com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager>()
        val parseResult = ParseResult(
            ast = null,
            compilationUnit = mockk(relaxed = true),
            sourceUnit = mockk(relaxed = true),
            diagnostics = emptyList(),
            symbolTable = mockk(relaxed = true),
            astModel = mockk(relaxed = true),
            tokenIndex = null,
        )
        every { workerSessionManager.parse(capture(requestSlot)) } returns parseResult

        val workspaceManager = mockk<WorkspaceManager>()
        every { workspaceManager.getClasspathForFile(any(), any()) } returns emptyList()
        every { workspaceManager.getSourceRoots() } returns listOf(tempDir)
        every { workspaceManager.getWorkspaceSources() } returns listOf(workspaceSource)

        val orchestrator = CompilationOrchestrator(
            CompilationOrchestratorDependencies(
                cacheService = CompilationCacheService(),
                workerSessionManager = workerSessionManager,
                workspaceManager = workspaceManager,
                symbolIndexer = mockk(relaxed = true),
                parseAccessor = mockk(relaxed = true) {
                    every { isSuspiciousScript(any(), any()) } returns false
                },
                resultMapper = CompilationResultMapper(),
                ioDispatcher = Dispatchers.Unconfined,
                errorHandler = CompilationErrorHandler(),
            ),
        )

        val result = orchestrator.ensureCompiled(URI.create(targetUri.toString()))

        assertNotNull(result)
        // ParseMode.MINIMAL ensures workspace sources are skipped during parsing (Issue #743)
        assertEquals(ParseMode.MINIMAL, requestSlot.captured.parseMode)
    }
}
