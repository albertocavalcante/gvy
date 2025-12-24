package com.github.albertocavalcante.groovylsp.providers.testing

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
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

    @BeforeEach
    fun setup() {
        TestFrameworkRegistry.registerIfAbsent(SpockTestDetector())
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
        val mockWorkspaceManager = mockk<com.github.albertocavalcante.groovylsp.compilation.WorkspaceManager>()

        every { mockService.workspaceManager } returns mockWorkspaceManager
        every { mockWorkspaceManager.getWorkspaceSourceUris() } returns listOf(uri)
        coEvery { mockService.getValidParseResult(uri) } returns parseResult

        val testProvider = TestDiscoveryProvider(mockService)
        val suites = testProvider.discoverTests("file:///")

        assertEquals(1, suites.size)
        assertEquals("com.example.MySpec", suites[0].suite)
        assertEquals(1, suites[0].tests.size)
        assertEquals("should work", suites[0].tests[0].test)
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
        val mockWorkspaceManager = mockk<com.github.albertocavalcante.groovylsp.compilation.WorkspaceManager>()

        every { mockService.workspaceManager } returns mockWorkspaceManager
        every { mockWorkspaceManager.getWorkspaceSourceUris() } returns listOf(uri)
        coEvery { mockService.getValidParseResult(uri) } returns parseResult

        val testProvider = TestDiscoveryProvider(mockService)
        val suites = testProvider.discoverTests("file:///")

        assertTrue(suites.isEmpty())
    }
}
