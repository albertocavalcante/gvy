package com.github.albertocavalcante.groovylsp.providers.codelens

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class TestCodeLensProviderTest {
    @Test
    fun `should provide Run and Debug CodeLenses for Spock features`() = runBlocking {
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

        // Use CONVERSION phase to preserve statement labels (required for Spock block detection)
        // and avoid Spock class resolution issues (Spock not on test classpath)
        val realService = GroovyCompilationService()
        realService.compile(uri, content, compilePhase = Phases.CONVERSION)
        val parseResult = realService.getParseResult(uri)!!

        val mockService = mockk<GroovyCompilationService>()
        every { mockService.getParseResult(uri) } returns parseResult

        val provider = TestCodeLensProvider(mockService)
        val codeLenses = provider.provideCodeLenses(uri)

        // Should have CodeLenses (the exact count depends on if we're generating class-level too)
        assertTrue(codeLenses.isNotEmpty(), "Expected at least some CodeLenses but got none")

        // Find method-level CodeLens
        val runCodeLens = codeLenses.find { it.command.title.contains("Run") && !it.command.title.contains("All") }
        val debugCodeLens = codeLenses.find { it.command.title.contains("Debug") && !it.command.title.contains("All") }

        assertNotNull(runCodeLens, "Expected to find a 'Run' CodeLens")
        assertNotNull(debugCodeLens, "Expected to find a 'Debug' CodeLens")

        assertEquals("groovy.test.run", runCodeLens!!.command.command)
        assertEquals("groovy.test.debug", debugCodeLens!!.command.command)
    }
}
