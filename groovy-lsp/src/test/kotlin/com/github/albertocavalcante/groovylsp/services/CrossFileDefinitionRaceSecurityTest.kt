package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.compilation.CompilationResult
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.net.URI
import java.time.Duration
import kotlin.test.fail

/**
 * SECURITY TESTS for PR #794 - while(true) loop vulnerabilities.
 *
 * These tests are designed to EXPOSE potential bugs in the ensureAllOpenDocumentsCompiled()
 * implementation that uses a while(true) loop without safeguards.
 *
 * CRITICAL VULNERABILITIES TESTED:
 * 1. Infinite loop when compilation always fails
 * 2. Timeout when compilation takes too long
 * 3. Circular dependencies
 * 4. Memory exhaustion with many files
 * 5. Concurrent modification during loop
 * 6. Exception handling in loop
 *
 * WITHOUT SAFEGUARDS: These tests will FAIL/TIMEOUT
 * WITH SAFEGUARDS: These tests should PASS
 */
class CrossFileDefinitionRaceSecurityTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var service: GroovyTextDocumentService
    private lateinit var scope: CoroutineScope

    @BeforeEach
    fun setUp() {
        compilationService = GroovyCompilationService()
        scope = CoroutineScope(Dispatchers.Default)
        service = GroovyTextDocumentService(
            coroutineScope = scope,
            compilationService = compilationService,
            options = GroovyTextDocumentServiceOptions(
                client = { null },
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        compilationService.clearCaches()
    }

    /**
     * VULNERABILITY TEST 1: Infinite Loop Prevention
     *
     * Tests that the loop does NOT run forever when compilation continuously fails.
     * Without MAX_ITERATIONS safeguard, this test will TIMEOUT.
     */
    @Test
    fun `should not loop infinitely when symbol storage always returns null`() = runTest {
        // This test exposes the infinite loop bug when getSymbolStorage always returns null
        // but compile() succeeds, causing compiledAny to be true in every iteration

        // Create a mock compilation service that compiles but never indexes
        val mockCompilationService = object : GroovyCompilationService() {
            private var compileCount = 0

            override fun compile(uri: URI, content: String): CompilationResult {
                compileCount++
                println("[TEST] compile() called $compileCount times for $uri")

                // Simulate successful compilation but failed indexing
                return super.compile(uri, content).also {
                    // Clear the storage to simulate indexing failure
                    clearCaches()
                }
            }

            override fun getSymbolStorage(uri: URI): SymbolIndex? {
                // Always return null to simulate indexing failure
                return null
            }
        }

        val testService = GroovyTextDocumentService(
            coroutineScope = scope,
            compilationService = mockCompilationService,
            options = GroovyTextDocumentServiceOptions(
                client = { null },
            ),
        )

        // Open a file
        val testUri = "file:///test/Test.groovy"
        val testContent = "class Test { }"
        testService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(testUri, "groovy", 1, testContent),
            ),
        )

        // Request definition - this should NOT hang forever
        // With safeguards: completes within timeout
        // Without safeguards: TIMES OUT (infinite loop)
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val params = DefinitionParams(
                TextDocumentIdentifier(testUri),
                Position(0, 6), // "Test" in "class Test"
            )

            val result = testService.definition(params).get()

            // Should return empty result, not hang
            assertTrue(result.isLeft, "Should return Left (List<Location>)")
            // Note: Result may be empty since storage is null, but should NOT hang
        }
    }

    /**
     * VULNERABILITY TEST 2: Timeout Protection
     *
     * Tests that the loop does NOT block indefinitely waiting for jobs.
     * Without MAX_TIMEOUT_MS safeguard, this test will TIMEOUT.
     */
    @Test
    fun `should timeout when waiting for diagnostic jobs that never complete`() = runTest {
        // This test exposes the timeout vulnerability when joinAll() blocks forever

        // Strategy: Open file and immediately request definition before diagnostics job completes
        // The ensureAllOpenDocumentsCompiled() will wait for the job
        // We simulate a slow job by using a large workspace

        val testUri = "file:///test/SlowFile.groovy"
        val testContent = """
            class SlowFile {
                // Large file to slow down compilation
                ${"void method$it() {}\n".repeat(100)}
            }
        """.trimIndent()

        testService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(testUri, "groovy", 1, testContent),
            ),
        )

        // Immediately request definition (race condition)
        // Should timeout within reasonable time, not wait forever
        assertTimeoutPreemptively(Duration.ofSeconds(35)) {
            val params = DefinitionParams(
                TextDocumentIdentifier(testUri),
                Position(0, 6),
            )

            val result = testService.definition(params).get()
            assertNotNull(result, "Should return result within timeout")
        }
    }

    /**
     * VULNERABILITY TEST 3: Circular Dependency Handling
     *
     * Tests that circular dependencies don't cause infinite compilation loop.
     * Without proper cycle detection, this test will TIMEOUT.
     */
    @Test
    fun `should handle circular dependencies without infinite loop`() = runTest {
        // File A references B, File B references A
        val fileAUri = "file:///test/ClassA.groovy"
        val fileAContent = """
            class ClassA {
                ClassB b
            }
        """.trimIndent()

        val fileBUri = "file:///test/ClassB.groovy"
        val fileBContent = """
            class ClassB {
                ClassA a
            }
        """.trimIndent()

        // Open both files (creates circular reference)
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(fileAUri, "groovy", 1, fileAContent),
            ),
        )
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(fileBUri, "groovy", 1, fileBContent),
            ),
        )

        // Request definition - should NOT hang due to circular dependency
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val params = DefinitionParams(
                TextDocumentIdentifier(fileAUri),
                Position(1, 8), // "ClassB" reference
            )

            val result = service.definition(params).get()
            assertNotNull(result, "Should handle circular dependencies")
        }
    }

    /**
     * VULNERABILITY TEST 4: Memory Exhaustion (Large Workspace)
     *
     * Tests that opening many files doesn't cause OOM or excessive delay.
     * Without backpressure, this test may TIMEOUT or OOM.
     */
    @Test
    fun `should handle large workspace without excessive delay`() = runTest {
        // Open 50 files (reduced from 100 for test speed, but still stress test)
        repeat(50) { i ->
            val uri = "file:///test/LargeFile$i.groovy"
            val content = """
                class LargeFile$i {
                    void method1() {}
                    void method2() {}
                    void method3() {}
                }
            """.trimIndent()

            service.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(uri, "groovy", 1, content),
                ),
            )
        }

        // Request definition on last file - should complete within reasonable time
        // With safeguards: completes quickly (max 60s for 50 files)
        // Without safeguards: may take forever or OOM
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val params = DefinitionParams(
                TextDocumentIdentifier("file:///test/LargeFile0.groovy"),
                Position(0, 6),
            )

            val result = service.definition(params).get()
            assertNotNull(result, "Should complete for large workspace")
        }
    }

    /**
     * VULNERABILITY TEST 5: Concurrent Modification
     *
     * Tests that files opened DURING the compilation loop don't cause infinite loop.
     * Without proper handling, getAllUris() can keep growing, preventing convergence.
     */
    @Test
    fun `should handle files opened during compilation loop`() = runTest {
        // Open initial file
        val initialUri = "file:///test/Initial.groovy"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(initialUri, "groovy", 1, "class Initial {}"),
            ),
        )

        // Launch concurrent jobs:
        // Job 1: Request definition (triggers ensureAllOpenDocumentsCompiled)
        // Job 2: Keep opening new files during compilation
        val definitionJob = launch {
            delay(10) // Small delay to let file opener start
            val params = DefinitionParams(
                TextDocumentIdentifier(initialUri),
                Position(0, 6),
            )

            // Should complete even though new files are being opened
            withTimeout(15_000) {
                val result = service.definition(params).get()
                assertNotNull(result, "Should complete despite concurrent modifications")
            }
        }

        val fileOpenerJob = launch {
            repeat(20) { i ->
                delay(50) // Open files slowly during definition request
                val uri = "file:///test/Concurrent$i.groovy"
                service.didOpen(
                    DidOpenTextDocumentParams(
                        TextDocumentItem(uri, "groovy", 1, "class Concurrent$i {}"),
                    ),
                )
            }
        }

        // Both jobs should complete within timeout
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            definitionJob.join()
            fileOpenerJob.join()
        }
    }

    /**
     * VULNERABILITY TEST 6: Exception Handling in Loop
     *
     * Tests that compilation exceptions don't cause infinite retry loop.
     * Without proper exception handling, this test will TIMEOUT.
     */
    @Test
    fun `should not loop infinitely when compilation throws exceptions`() = runTest {
        // Create a mock service that always throws on compile
        val mockCompilationService = object : GroovyCompilationService() {
            private var exceptionCount = 0

            override fun compile(uri: URI, content: String): CompilationResult {
                exceptionCount++
                println("[TEST] compile() throwing exception $exceptionCount")

                if (exceptionCount > 5) {
                    fail("compile() called more than 5 times - infinite loop detected")
                }

                // Throw exception to simulate compilation failure
                throw RuntimeException("Simulated compilation failure")
            }
        }

        val testService = GroovyTextDocumentService(
            coroutineScope = scope,
            compilationService = mockCompilationService,
            options = GroovyTextDocumentServiceOptions(
                client = { null },
            ),
        )

        // Open a file
        val testUri = "file:///test/FailingFile.groovy"
        testService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(testUri, "groovy", 1, "class FailingFile {}"),
            ),
        )

        // Request definition - should NOT retry forever
        // With exception handling: completes quickly
        // Without exception handling: infinite loop of exceptions
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            val params = DefinitionParams(
                TextDocumentIdentifier(testUri),
                Position(0, 6),
            )

            // Should handle exception gracefully and return
            try {
                val result = testService.definition(params).get()
                // Result may be empty due to compilation failure, but should NOT hang
                assertNotNull(result)
            } catch (e: Exception) {
                // Exception is acceptable, but should NOT timeout
                println("[TEST] Definition failed with exception (acceptable): ${e.message}")
            }
        }
    }

    /**
     * VULNERABILITY TEST 7: Iteration Count Monitoring
     *
     * Tests that loop iteration count is bounded.
     * This test monitors that the loop doesn't run hundreds of times.
     */
    @Test
    fun `should not exceed reasonable iteration count`() = runTest {
        // This is a meta-test to ensure MAX_ITERATIONS is working
        // We open a few files and verify definition completes quickly

        repeat(10) { i ->
            val uri = "file:///test/File$i.groovy"
            service.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(uri, "groovy", 1, "class File$i {}"),
                ),
            )
        }

        // Definition should complete in < 5 seconds for 10 files
        // If loop is iterating excessively, this will timeout
        assertTimeout(Duration.ofSeconds(5)) {
            val params = DefinitionParams(
                TextDocumentIdentifier("file:///test/File0.groovy"),
                Position(0, 6),
            )

            val result = service.definition(params).get()
            assertNotNull(result)
        }
    }
}
