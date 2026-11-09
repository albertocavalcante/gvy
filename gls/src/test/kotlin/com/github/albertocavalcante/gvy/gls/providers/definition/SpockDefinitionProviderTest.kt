package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

class SpockDefinitionProviderTest {
    @Test
    fun `default compilation navigates unroll root through locations and links`(): Unit = runBlocking {
        val content = """
            import spock.lang.Specification
            class Example extends Specification {
                def "uses #value"() {
                    expect:
                    value > 0
                    where:
                    value | expected
                    1 | true
                }
            }
        """.trimIndent()
        val uri = URI.create("file:///Example.groovy")
        val service = GroovyCompilationService()
        try {
            service.compile(uri, content)
            val provider = DefinitionProvider(service)
            val position = Position(2, 17)
            val definitions = provider.provideDefinitions(uri.toString(), position).toList()
            val links = provider.provideDefinitionLinks(uri.toString(), position).toList()
            assertEquals(6, definitions.single().range.start.line)
            assertEquals(definitions.single().range, links.single().targetSelectionRange)
            assertEquals(uri.toString(), links.single().targetUri)
            val body = provider.provideDefinitions(uri.toString(), Position(4, 10)).toList()
            assertEquals(6, body.single().range.start.line)
        } finally {
            service.clearCaches()
        }
    }
}
