package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.worker.WorkerSessionManager
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
import kotlin.test.assertTrue

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
        val workerSessionManager = mockk<WorkerSessionManager>()
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
        every { workspaceManager.getConfigurationFingerprint() } returns "test-fingerprint"

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

    @Test
    fun `cache invalidated when configuration fingerprint changes`() = runBlocking {
        val content = "class Test {}"
        val uri = URI.create("file:///Test.groovy")

        var parseCallCount = 0
        val workerSessionManager = mockk<WorkerSessionManager>()
        val parseResult = ParseResult(
            ast = mockk(relaxed = true),
            compilationUnit = mockk(relaxed = true),
            sourceUnit = mockk(relaxed = true),
            diagnostics = emptyList(),
            symbolTable = mockk(relaxed = true),
            astModel = mockk(relaxed = true),
            tokenIndex = null,
        )
        every { workerSessionManager.parse(any()) } answers {
            parseCallCount++
            parseResult
        }

        var currentFingerprint = "fingerprint-v1"
        val workspaceManager = mockk<WorkspaceManager>()
        every { workspaceManager.getClasspathForFile(any(), any()) } returns emptyList()
        every { workspaceManager.getSourceRoots() } returns emptyList()
        every { workspaceManager.getWorkspaceSources() } returns emptyList()
        every { workspaceManager.getConfigurationFingerprint() } answers { currentFingerprint }

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

        // First compile - should parse
        orchestrator.compile(uri, content)
        assertEquals(1, parseCallCount, "First compile should trigger parse")

        // Second compile with same fingerprint - should use cache
        orchestrator.compile(uri, content)
        assertEquals(1, parseCallCount, "Same fingerprint should use cache, not re-parse")

        // Change fingerprint (simulates dependency change)
        currentFingerprint = "fingerprint-v2"

        // Third compile with different fingerprint - should re-parse (Issue #743)
        orchestrator.compile(uri, content)
        assertEquals(2, parseCallCount, "Changed fingerprint should invalidate cache and trigger re-parse")
    }

    @Test
    fun `compile uses bounded workspace sources when dependency info exists`() = runBlocking {
        val content = "class Test {}"
        val uri = URI.create("file:///Test.groovy")
        val depUri = URI.create("file:///Dep.groovy")

        val requestSlot = slot<ParseRequest>()
        val workerSessionManager = mockk<WorkerSessionManager>()
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

        val fullWorkspaceSources = listOf(
            tempDir.resolve("A.groovy"),
            tempDir.resolve("B.groovy"),
            tempDir.resolve("C.groovy"),
        )
        val boundedSources = listOf(tempDir.resolve("A.groovy"))

        val boundedUrisSlot = slot<Set<URI>>()
        val workspaceManager = mockk<WorkspaceManager>()
        every { workspaceManager.getClasspathForFile(any(), any()) } returns emptyList()
        every { workspaceManager.getSourceRoots() } returns listOf(tempDir)
        every { workspaceManager.getWorkspaceSources() } returns fullWorkspaceSources
        every { workspaceManager.getBoundedWorkspaceSources(capture(boundedUrisSlot)) } returns boundedSources
        every { workspaceManager.getConfigurationFingerprint() } returns "test-fingerprint"

        // DependencyGraph with info for this URI
        val dependencyGraph = DependencyGraph()
        dependencyGraph.addDependency(uri, depUri)

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
                dependencyGraph = dependencyGraph,
            ),
        )

        orchestrator.compile(uri, content)

        // Should use bounded sources, not full workspace
        assertEquals(boundedSources, requestSlot.captured.workspaceSources)

        // Verify the correct URIs were passed to getBoundedWorkspaceSources
        assertEquals(
            setOf(depUri),
            boundedUrisSlot.captured,
            "Should pass dependency URIs to getBoundedWorkspaceSources",
        )
    }

    @Test
    fun `compile falls back to full workspace when no dependency info exists`() = runBlocking {
        val content = "class Test {}"
        val uri = URI.create("file:///Test.groovy")

        val requestSlot = slot<ParseRequest>()
        val workerSessionManager = mockk<WorkerSessionManager>()
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

        val fullWorkspaceSources = listOf(
            tempDir.resolve("A.groovy"),
            tempDir.resolve("B.groovy"),
            tempDir.resolve("C.groovy"),
        )

        val workspaceManager = mockk<WorkspaceManager>()
        every { workspaceManager.getClasspathForFile(any(), any()) } returns emptyList()
        every { workspaceManager.getSourceRoots() } returns listOf(tempDir)
        every { workspaceManager.getWorkspaceSources() } returns fullWorkspaceSources
        every { workspaceManager.getConfigurationFingerprint() } returns "test-fingerprint"

        // Empty DependencyGraph - no info for this URI
        val dependencyGraph = DependencyGraph()

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
                dependencyGraph = dependencyGraph,
            ),
        )

        orchestrator.compile(uri, content)

        // Should fall back to full workspace sources
        assertEquals(fullWorkspaceSources, requestSlot.captured.workspaceSources)
    }

    @Test
    fun `compile falls back to full workspace when dependencyGraph is null`() = runBlocking {
        val content = "class Test {}"
        val uri = URI.create("file:///Test.groovy")

        val requestSlot = slot<ParseRequest>()
        val workerSessionManager = mockk<WorkerSessionManager>()
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

        val fullWorkspaceSources = listOf(
            tempDir.resolve("A.groovy"),
            tempDir.resolve("B.groovy"),
        )

        val workspaceManager = mockk<WorkspaceManager>()
        every { workspaceManager.getClasspathForFile(any(), any()) } returns emptyList()
        every { workspaceManager.getSourceRoots() } returns listOf(tempDir)
        every { workspaceManager.getWorkspaceSources() } returns fullWorkspaceSources
        every { workspaceManager.getConfigurationFingerprint() } returns "test-fingerprint"

        // No dependency graph (null)
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
                dependencyGraph = null,
            ),
        )

        orchestrator.compile(uri, content)

        // Should fall back to full workspace sources when dependencyGraph is null
        assertEquals(fullWorkspaceSources, requestSlot.captured.workspaceSources)
    }
}
