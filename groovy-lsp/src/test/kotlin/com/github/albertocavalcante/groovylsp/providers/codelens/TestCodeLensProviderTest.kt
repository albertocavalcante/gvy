package com.github.albertocavalcante.groovylsp.providers.codelens

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
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

        // Use SEMANTIC_ANALYSIS to avoid Spock's renaming/label-stripping transformations
        val realService = GroovyCompilationService()
        realService.compile(uri, content, compilePhase = Phases.SEMANTIC_ANALYSIS)
        val parseResult = realService.getParseResult(uri)!!

        val mockService = mockk<GroovyCompilationService>()
        every { mockService.getParseResult(uri) } returns parseResult

        val provider = TestCodeLensProvider(mockService)
        val codeLenses = provider.provideCodeLenses(uri)

        // 2 CodeLenses per feature (Run + Debug)
        assertEquals(2, codeLenses.size)

        assertEquals("▶ Run Test", codeLenses[0].command.title)
        assertEquals("groovy.test.run", codeLenses[0].command.command)

        assertEquals("🐛 Debug Test", codeLenses[1].command.title)
        assertEquals("groovy.test.debug", codeLenses[1].command.command)
    }
}
