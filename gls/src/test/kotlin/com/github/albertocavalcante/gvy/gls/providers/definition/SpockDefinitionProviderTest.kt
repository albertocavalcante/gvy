package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `an aliased Specification import keeps data variable navigation`(): Unit = runBlocking {
        val content = """
            import spock.lang.Specification as Spec
            class Example extends Spec {
                def "uses #value"() {
                    expect:
                    value > 0
                    where:
                    value | expected
                    1 | true
                }
            }
        """.trimIndent()
        val definitions = definitionsAt(content, Position(4, 10))
        assertEquals(6, definitions.single().range.start.line)
    }

    @Test
    fun `a helper next to a spec does not resolve data variables`(): Unit = runBlocking {
        val content = """
            import spock.lang.Specification
            class Helper {
                def check() {
                    expect:
                    value > 0
                    where:
                    value | expected
                    1 | true
                }
            }
            class ExampleSpec extends Specification {
                def "uses #value"() {
                    expect:
                    value > 0
                    where:
                    value | expected
                    1 | true
                }
            }
        """.trimIndent()
        assertTrue(definitionsAt(content, Position(4, 10)).none { it.range.start.line == 6 })
        assertEquals(15, definitionsAt(content, Position(13, 10)).single().range.start.line)
    }

    @Test
    fun `a local class named Specification is not Spock even with a spock star import`(): Unit = runBlocking {
        val content = """
            import spock.lang.*
            class Specification {}
            class Rule extends Specification {
                def check() {
                    expect:
                    value > 0
                    where:
                    value | expected
                    1 | true
                }
            }
        """.trimIndent()
        assertTrue(definitionsAt(content, Position(5, 10)).none { it.range.start.line == 7 })
    }

    @Test
    fun `an uncertain external base keeps the previous detector`(): Unit = runBlocking {
        val content = """
            class ExampleSpec extends BaseSpec {
                def "uses #value"() {
                    expect:
                    value > 0
                    where:
                    value | expected
                    1 | true
                }
            }
        """.trimIndent()
        assertTrue(definitionsAt(content, Position(3, 10)).none { it.range.start.line == 5 })
    }

    private suspend fun definitionsAt(content: String, position: Position) = GroovyCompilationService().let { service ->
        val uri = URI.create("file:///ExampleSpec.groovy")
        try {
            service.compile(uri, content)
            DefinitionProvider(service).provideDefinitions(uri.toString(), position).toList()
        } finally {
            service.clearCaches()
        }
    }
}
