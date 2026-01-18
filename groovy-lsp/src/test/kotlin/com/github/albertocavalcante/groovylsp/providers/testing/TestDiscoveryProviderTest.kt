package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.compilation.WorkspaceManager
import com.github.albertocavalcante.groovytesting.registry.TestFrameworkRegistry
import com.github.albertocavalcante.groovytesting.spock.SpockTestDetector
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class TestDiscoveryProviderTest {

    private lateinit var registry: TestFrameworkRegistry

    @BeforeEach
    fun setup() {
        registry = TestFrameworkRegistry()
        registry.registerIfAbsent(SpockTestDetector())
    }

    @Test
    fun `should discover tests in Spock specification`() = runBlocking {
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
    fun `should return correct class line for test suite`() = runBlocking {
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
    fun `should skip non-Spock classes`() = runBlocking {
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
}
