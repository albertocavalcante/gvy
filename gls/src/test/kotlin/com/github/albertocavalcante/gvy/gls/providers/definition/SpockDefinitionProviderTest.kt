// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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

    @Test
    fun `an escaped feature name navigates from its placeholder`(): Unit = runBlocking {
        val content = """
            import spock.lang.Specification
            class Example extends Specification {
                def "say \"#value\""() {
                    expect:
                    value
                    where:
                    value << [1]
                }
            }
        """.trimIndent()
        val header = content.lines()[2]
        val definitions = definitionsAt(content, Position(2, header.indexOf("#value") + 2))
        assertEquals(6, definitions.single().range.start.line)
    }

    @Test
    fun `only the selected unroll pattern navigates`(): Unit = runBlocking {
        val content = """
            import spock.lang.Specification
            import spock.lang.Unroll
            class Example extends Specification {
                @Unroll("#b")
                def "uses #a"() {
                    expect:
                    a == b
                    where:
                    a | b
                    1 | 1
                }
            }
        """.trimIndent()
        val name = content.lines()[4]
        assertTrue(definitionsAt(content, Position(4, name.indexOf("#a") + 1)).none { it.range.start.line == 8 })
        val annotation = content.lines()[3]
        assertEquals(8, definitionsAt(content, Position(3, annotation.indexOf("#b") + 1)).single().range.start.line)
    }

    @Test
    fun `a module-local Unroll annotation is not Spock's`(): Unit = runBlocking {
        val content = """
            import spock.lang.Specification
            @interface Unroll { String value() }
            class Example extends Specification {
                @Unroll("#a")
                def "feature"() {
                    expect:
                    a
                    where:
                    a << [1]
                }
            }
        """.trimIndent()
        val annotation = content.lines()[3]
        assertTrue(definitionsAt(content, Position(3, annotation.indexOf("#a") + 1)).none { it.range.start.line == 8 })
    }

    @Test
    fun `a rolled-up feature does not navigate from its name`(): Unit = runBlocking {
        val content = """
            import spock.lang.Rollup
            import spock.lang.Specification
            class Example extends Specification {
                @Rollup
                def "uses #a"() {
                    expect:
                    a
                    where:
                    a << [1]
                }
            }
        """.trimIndent()
        val name = content.lines()[4]
        assertTrue(definitionsAt(content, Position(4, name.indexOf("#a") + 1)).none { it.range.start.line == 8 })
    }

    @Test
    fun `a spec-level Unroll pattern does not navigate from the feature header`(): Unit = runBlocking {
        val content = """
            import spock.lang.Specification
            import spock.lang.Unroll
            @Unroll("#a")
            class Example extends Specification {
                def "feature"() {
                    expect:
                    a
                    where:
                    a << [1]
                }
            }
        """.trimIndent()
        val annotation = content.lines()[2]
        assertTrue(definitionsAt(content, Position(2, annotation.indexOf("#a") + 1)).none { it.range.start.line == 8 })
        val header = content.lines()[4]
        assertTrue(definitionsAt(content, Position(4, header.indexOf("feature") + 1)).none { it.range.start.line == 8 })
    }

    @Test
    fun `a star-imported Unroll keeps the previous header matching`(): Unit = runBlocking {
        val content = """
            import spock.lang.*
            class Example extends Specification {
                @Unroll("#a")
                def "feature"() {
                    expect:
                    a
                    where:
                    a << [1]
                }
            }
        """.trimIndent()
        val annotation = content.lines()[2]
        assertEquals(7, definitionsAt(content, Position(2, annotation.indexOf("#a") + 1)).single().range.start.line)
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
