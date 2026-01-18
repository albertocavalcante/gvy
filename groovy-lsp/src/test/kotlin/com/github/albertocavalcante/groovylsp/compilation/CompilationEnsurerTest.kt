package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilationEnsurerTest {

    @Test
    fun `ensureAllCompiled returns immediately when all documents compiled`() = runBlocking {
        // Given
        val uri1 = URI.create("file:///test1.groovy")
        val uri2 = URI.create("file:///test2.groovy")

        val documentProvider = mockk<DocumentProvider>()
        every { documentProvider.getAllUris() } returns setOf(uri1, uri2)

        val compilationService = mockk<GroovyCompilationService>()
        // Both documents are already compiled (getSymbolStorage returns non-null)
        every { compilationService.getSymbolStorage(uri1) } returns mockk()
        every { compilationService.getSymbolStorage(uri2) } returns mockk()

        val diagnosticJobs = ConcurrentHashMap<URI, Job>()

        // When
        val ensurer = CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticJobs,
        )
        ensurer.ensureAllCompiled()

        // Then - should return immediately without compiling anything
        coVerify(exactly = 0) { compilationService.compile(any(), any()) }
    }

    @Test
    fun `ensureAllCompiled respects iteration limit`() = runBlocking {
        // Given
        val uri = URI.create("file:///test.groovy")

        val documentProvider = mockk<DocumentProvider>()
        every { documentProvider.getAllUris() } returns setOf(uri)
        every { documentProvider.get(uri) } returns "class Test {}"

        val compilationService = mockk<GroovyCompilationService>()
        // Always return null to simulate uncompiled state (will trigger iteration limit)
        every { compilationService.getSymbolStorage(uri) } returns null
        coEvery { compilationService.compile(uri, any()) } returns mockk()

        val diagnosticJobs = ConcurrentHashMap<URI, Job>()

        // When
        val ensurer = CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticJobs,
            maxIterations = 3,
        )
        ensurer.ensureAllCompiled()

        // Then - should stop after maxIterations
        // Each iteration compiles once, so 3 iterations = 3 compile calls
        coVerify(exactly = 3) { compilationService.compile(uri, any()) }
    }

    @Test
    fun `ensureAllCompiled respects timeout`() = runBlocking {
        // Given
        val uri = URI.create("file:///test.groovy")

        val documentProvider = mockk<DocumentProvider>()
        every { documentProvider.getAllUris() } returns setOf(uri)
        every { documentProvider.get(uri) } returns "class Test {}"

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getSymbolStorage(uri) } returns null
        coEvery { compilationService.compile(uri, any()) } coAnswers {
            delay(100) // Simulate slow compilation
            mockk()
        }

        val diagnosticJobs = ConcurrentHashMap<URI, Job>()

        // When
        val ensurer = CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticJobs,
            maxTimeMs = 250, // Short timeout
        )

        val startTime = System.currentTimeMillis()
        ensurer.ensureAllCompiled()
        val elapsed = System.currentTimeMillis() - startTime

        // Then - should timeout and not continue indefinitely
        assertTrue(elapsed < 500, "Should timeout within reasonable time, took ${elapsed}ms")
    }

    @Test
    fun `ensureAllCompiled tracks failed URIs to avoid retry loops`() = runBlocking {
        // Given
        val uri = URI.create("file:///test.groovy")

        val documentProvider = mockk<DocumentProvider>()
        every { documentProvider.getAllUris() } returns setOf(uri)
        every { documentProvider.get(uri) } returns "class Test {}"

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getSymbolStorage(uri) } returns null
        // First call throws exception, subsequent calls would succeed if retried
        var callCount = 0
        coEvery { compilationService.compile(uri, any()) } coAnswers {
            callCount++
            if (callCount == 1) {
                throw RuntimeException("Compilation failed")
            }
            mockk()
        }

        val diagnosticJobs = ConcurrentHashMap<URI, Job>()

        // When
        val ensurer = CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticJobs,
            maxIterations = 5,
        )
        ensurer.ensureAllCompiled()

        // Then - should only attempt once and not retry failed URIs
        assertEquals(1, callCount, "Should only attempt compilation once for failed URI")
    }

    @Test
    fun `ensureAllCompiled waits for pending diagnostic jobs`() = runBlocking {
        // Given
        val uri = URI.create("file:///test.groovy")

        val documentProvider = mockk<DocumentProvider>()
        every { documentProvider.getAllUris() } returns setOf(uri)

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getSymbolStorage(uri) } returns mockk()

        val diagnosticJobs = ConcurrentHashMap<URI, Job>()

        // Create a pending job
        var jobCompleted = false
        val pendingJob = launch {
            delay(50)
            jobCompleted = true
        }
        diagnosticJobs[uri] = pendingJob

        // When
        val ensurer = CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticJobs,
        )
        ensurer.ensureAllCompiled()

        // Then - should have waited for the job to complete
        assertTrue(jobCompleted, "Should wait for pending diagnostic jobs")
        assertFalse(pendingJob.isActive, "Pending job should be completed")
    }

    @Test
    fun `ensureAllCompiled compiles unindexed documents`() = runBlocking {
        // Given
        val uri1 = URI.create("file:///compiled.groovy")
        val uri2 = URI.create("file:///uncompiled.groovy")

        val documentProvider = mockk<DocumentProvider>()
        every { documentProvider.getAllUris() } returns setOf(uri1, uri2)
        every { documentProvider.get(uri2) } returns "class Uncompiled {}"

        val compilationService = mockk<GroovyCompilationService>()
        // uri1 is already compiled
        every { compilationService.getSymbolStorage(uri1) } returns mockk()
        // uri2 is not compiled initially, then becomes compiled after compile()
        var uri2Compiled = false
        every { compilationService.getSymbolStorage(uri2) } answers {
            if (uri2Compiled) mockk() else null
        }
        coEvery { compilationService.compile(uri2, any()) } coAnswers {
            uri2Compiled = true
            mockk()
        }

        val diagnosticJobs = ConcurrentHashMap<URI, Job>()

        // When
        val ensurer = CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticJobs,
        )
        ensurer.ensureAllCompiled()

        // Then - should only compile unindexed document
        coVerify(exactly = 1) { compilationService.compile(uri2, "class Uncompiled {}") }
        coVerify(exactly = 0) { compilationService.compile(uri1, any()) }
    }

    @Test
    fun `ensureAllCompiled handles null content gracefully`() = runBlocking {
        // Given
        val uri = URI.create("file:///test.groovy")

        val documentProvider = mockk<DocumentProvider>()
        every { documentProvider.getAllUris() } returns setOf(uri)
        every { documentProvider.get(uri) } returns null // Document removed

        val compilationService = mockk<GroovyCompilationService>()
        every { compilationService.getSymbolStorage(uri) } returns null

        val diagnosticJobs = ConcurrentHashMap<URI, Job>()

        // When
        val ensurer = CompilationEnsurer(
            documentProvider = documentProvider,
            compilationService = compilationService,
            diagnosticJobs = diagnosticJobs,
        )
        ensurer.ensureAllCompiled()

        // Then - should not attempt to compile document with null content
        coVerify(exactly = 0) { compilationService.compile(any(), any()) }
    }
}
