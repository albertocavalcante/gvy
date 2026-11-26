// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.testing

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.WorkspaceManager
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.junit.JUnit5TestDetector
import com.github.albertocavalcante.gvy.testing.registry.TestFrameworkRegistry
import com.github.albertocavalcante.gvy.testing.spock.SpockTestDetector
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class TestDiscoveryProviderTest {

    private lateinit var registry: TestFrameworkRegistry

    @BeforeEach
    fun setup() {
        registry = TestFrameworkRegistry()
        registry.registerIfAbsent(SpockTestDetector())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cold discovery publishes facts from the same source as the discovered test`(
        open: Boolean,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val disk = "class Checks { @org.junit.jupiter.api.Test void saved() {} }"
        val buffer = "class Checks { @org.junit.jupiter.api.Test void unsaved() {} }"
        val uri = Files.writeString(root.resolve("Checks.groovy"), disk).toUri()
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(
            TestDiscoveryProviderTest::class.java.classLoader,
            documentProvider = documents,
        )
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            if (open) {
                documents.put(uri, buffer)
                compilation.onDocumentOpened(uri)
            }
            registry.registerIfAbsent(JUnit5TestDetector())
            val suites = TestDiscoveryProvider(compilation, registry).discoverTests(root.toUri().toString())
            val suite = suites.single()
            assertEquals("Checks", suite.suite)
            assertEquals(if (open) "unsaved" else "saved", suite.tests.single().test)
            val indexer = assertNotNull(compilation.captureSnapshotSource(uri).dependencies.semanticIndexer)
            val publication = assertNotNull(indexer.currentReferencePublication(uri))
            assertTrue(indexer.isCurrent(uri, if (open) buffer else disk))
            assertEquals(!open, publication.diskConfirmed)
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `cold discovery propagates detector failures without replacing their cause`(@TempDir root: Path): Unit =
        runBlocking {
            Files.writeString(root.resolve("Checks.groovy"), "class Checks {}")
            val compilation = GroovyCompilationService()
            try {
                compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
                val detectors = mockk<TestFrameworkRegistry>()
                val provider = TestDiscoveryProvider(compilation, detectors)
                val failures = listOf(
                    FileSystemException("detector.jar", null, "Detector unavailable"),
                    CancellationException("Discovery cancelled"),
                    AssertionError("Detector invariant"),
                )
                for (failure in failures) {
                    every { detectors.extractTests(any(), any(), any()) } throws failure
                    assertSame(failure, assertFails { provider.discoverTests(root.toUri().toString()) })
                }
            } finally {
                compilation.shutdown()
            }
        }

    @Test
    fun `should discover tests in Spock specification`(): Unit = runBlocking {
        val uri = URI.create("file:///MySpec.groovy")
        val content = """
            package com.example
            import spock.lang.Specification
            class MySpec extends Specification {
                def "should work"() {
                    expect: true
                }
            }
        """.trimIndent()

        // Use CONVERSION phase to preserve statement labels and avoid Spock class resolution issues
        val realService = GroovyCompilationService(TestDiscoveryProviderTest::class.java.classLoader)
        realService.compile(uri, content, compilePhase = Phases.CONVERSION)
        val parseResult = realService.getParseResult(uri)!!

        val mockService = mockk<GroovyCompilationService>()
        val mockWorkspaceManager = mockk<WorkspaceManager>()

        every { mockService.workspaceManager } returns mockWorkspaceManager
        every { mockWorkspaceManager.getWorkspaceSourceUris() } returns listOf(uri)
        coEvery { mockService.getValidParseResult(uri) } returns parseResult

        val testProvider = TestDiscoveryProvider(mockService, registry)
        val suites = testProvider.discoverTests("file:///")

        assertEquals(1, suites.size)
        assertEquals("com.example.MySpec", suites[0].suite)
        assertEquals(1, suites[0].tests.size)
        assertEquals("should work", suites[0].tests[0].test)
    }

    @Test
    fun `should return correct class line for test suite`(): Unit = runBlocking {
        // Test with multiple lines between class and first test method
        // to ensure we use the actual class line, not an estimate from test methods
        val uri = URI.create("file:///TestWithSetup.groovy")
        val content = """
            package com.example
            import spock.lang.Specification
            class TestWithSetup extends Specification {
                // Line 4: comment
                // Line 5: more comments
                def setup() {
                    // Line 7: setup code
                }
                // Line 9: blank
                // Line 10: more comments
                def "first test"() {
                    expect: true
                }
            }
        """.trimIndent()

        val realService = GroovyCompilationService(TestDiscoveryProviderTest::class.java.classLoader)
        realService.compile(uri, content, compilePhase = Phases.CONVERSION)
        val parseResult = realService.getParseResult(uri)!!

        val mockService = mockk<GroovyCompilationService>()
        val mockWorkspaceManager = mockk<WorkspaceManager>()

        every { mockService.workspaceManager } returns mockWorkspaceManager
        every { mockWorkspaceManager.getWorkspaceSourceUris() } returns listOf(uri)
        coEvery { mockService.getValidParseResult(uri) } returns parseResult

        val testProvider = TestDiscoveryProvider(mockService, registry)
        val suites = testProvider.discoverTests("file:///")

        assertEquals(1, suites.size)
        // Class is declared at line 3 (1-indexed)
        assertEquals(3, suites[0].line, "Class line should be the actual class declaration line")
        // First test method is at line 11 (1-indexed)
        assertEquals(11, suites[0].tests[0].line, "Test method line should be correct")
    }

    @Test
    fun `should skip non-Spock classes`(): Unit = runBlocking {
        val uri = URI.create("file:///RegularClass.groovy")
        val content = """
            class RegularClass {
                def method() {}
            }
        """.trimIndent()

        val realService = GroovyCompilationService(TestDiscoveryProviderTest::class.java.classLoader)
        realService.compile(uri, content)
        val parseResult = realService.getParseResult(uri)!!

        val mockService = mockk<GroovyCompilationService>()
        val mockWorkspaceManager = mockk<WorkspaceManager>()

        every { mockService.workspaceManager } returns mockWorkspaceManager
        every { mockWorkspaceManager.getWorkspaceSourceUris() } returns listOf(uri)
        coEvery { mockService.getValidParseResult(uri) } returns parseResult

        val testProvider = TestDiscoveryProvider(mockService, registry)
        val suites = testProvider.discoverTests("file:///")

        assertTrue(suites.isEmpty())
    }

    @Test
    fun `a local class named Specification is not a Spock suite even with a spock star import`(): Unit = runBlocking {
        val suites = discover(
            """
                import spock.lang.*
                class Specification {}
                class Rule extends Specification {
                    def "looks like a feature"() {
                        expect: true
                    }
                }
            """.trimIndent(),
        )

        assertTrue(suites.none { it.suite == "Rule" }, "Rule must not be discovered: $suites")
    }

    @Test
    fun `a spec extending a base spec is flagged as inheriting features`(): Unit = runBlocking {
        val suites = discover(
            """
            import spock.lang.Specification
            abstract class BaseSpec extends Specification {
                def "shared"() {
                    expect: true
                }
            }
            class ChildSpec extends BaseSpec {
                def "own"() {
                    expect: true
                }
            }
            """.trimIndent(),
        )

        val child = suites.single { it.suite == "ChildSpec" }
        assertTrue(child.inheritsTests, "ChildSpec inherits features: $suites")
        assertFalse(suites.single { it.suite == "BaseSpec" }.inheritsTests, "BaseSpec is complete: $suites")
    }

    @Test
    fun `an uncertain external base keeps the previous detector`(): Unit = runBlocking {
        val suites = discover(
            """
            class ExampleSpec extends BaseSpec {
                def "feature"() {
                    expect: true
                }
            }
            """.trimIndent(),
        )

        assertTrue(suites.isEmpty())
    }

    @Test
    fun `a plain extends Specification suite does not inherit tests at conversion`(): Unit = runBlocking {
        val suites = discover(
            """
            import spock.lang.Specification
            class PlainSpec extends Specification {
                def "feature"() {
                    expect: true
                }
            }
            """.trimIndent(),
            Phases.CONVERSION,
        )

        val plain = suites.single { it.suite == "PlainSpec" }
        assertFalse(plain.inheritsTests, "the unresolved base `Specification` is not a base spec: $suites")
    }

    private suspend fun discover(content: String, phase: Int = Phases.CANONICALIZATION) = run {
        val uri = URI.create("file:///Probe.groovy")
        val realService = GroovyCompilationService(TestDiscoveryProviderTest::class.java.classLoader)
        realService.compile(uri, content, compilePhase = phase)
        val parseResult = realService.getParseResult(uri)!!
        val mockService = mockk<GroovyCompilationService>()
        val mockWorkspaceManager = mockk<WorkspaceManager>()
        every { mockService.workspaceManager } returns mockWorkspaceManager
        every { mockWorkspaceManager.getWorkspaceSourceUris() } returns listOf(uri)
        coEvery { mockService.getValidParseResult(uri) } returns parseResult
        TestDiscoveryProvider(mockService, registry).discoverTests("file:///")
    }
}
