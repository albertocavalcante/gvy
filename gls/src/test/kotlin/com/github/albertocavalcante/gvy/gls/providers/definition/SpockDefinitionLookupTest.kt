package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.findNodeAt
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpockDefinitionLookupTest {
    @Test
    fun `compact features with identical data names resolve within the owning method`() {
        val content = "class Example extends spock.lang.Specification { " +
            "def \"first #value\"(){ expect: value > 0; where: value << [1] }; " +
            "def \"second #value\"(){ expect: value > 0; where: value << [2] } }"
        val module = GroovyParserFacade().parse(
            ParseRequest(URI.create("file:///Example.groovy"), content, compilePhase = Phases.CONVERSION),
        ).ast
        assertNotNull(module)
        val target = SpockDefinitionLookup.find(module, content, Position(0, content.lastIndexOf("#value") + 2))
        assertNotNull(target)
        assertEquals(content.lastIndexOf("value <<") + 1, target.columnNumber)
    }

    @Test
    fun `data declarations shadow field bindings`() {
        val content = """
            class Example extends spock.lang.Specification {
                def value = 1
                def "uses data"() {
                    expect:
                    value == 2
                    where:
                    value << [2]
                }
            }
        """.trimIndent()
        val module = GroovyParserFacade().parse(
            ParseRequest(
                URI.create("file:///Example.groovy"),
                content,
                classpath = listOf(
                    Path.of(spock.lang.Specification::class.java.protectionDomain.codeSource.location.toURI()),
                ),
                compilePhase = Phases.CANONICALIZATION,
            ),
        ).ast
        assertNotNull(module)
        val origin = module.findNodeAt(4, 10) as? VariableExpression
        assertNotNull(origin)
        assertTrue(origin.accessedVariable is FieldNode)
        assertEquals(7, SpockDefinitionLookup.find(module, content, Position(4, 10), origin)?.lineNumber)
    }

    @Test
    fun `annotated feature and unroll annotation roots resolve to the same header`() {
        val content = """
            import spock.lang.*
            class Example extends Specification {
                @Unroll("case #value")
                def "uses #value"() {
                    expect:
                    value > 0
                    where:
                    value | expected
                    1 | true
                }
            }
        """.trimIndent()
        val module = GroovyParserFacade().parse(
            ParseRequest(URI.create("file:///Example.groovy"), content, compilePhase = Phases.CONVERSION),
        ).ast
        assertNotNull(module)
        assertEquals(8, SpockDefinitionLookup.find(module, content, Position(3, 17))?.lineNumber)
        assertEquals(8, SpockDefinitionLookup.find(module, content, Position(2, 20))?.lineNumber)
    }

    @Test
    fun `unroll root navigates to header in owning feature only`() {
        val content = """
            import spock.lang.Specification
            class Example extends Specification {
                def "uses #value.name"() {
                    expect:
                    value != null
                    where:
                    value | expected
                    'a' | true
                }
                def "missing #value"() {
                    expect:
                    true
                }
            }
        """.trimIndent()
        val module = GroovyParserFacade().parse(
            ParseRequest(URI.create("file:///Example.groovy"), content, compilePhase = Phases.CONVERSION),
        ).ast
        assertNotNull(module)
        val target = SpockDefinitionLookup.find(module, content, Position(2, 17))
        assertNotNull(target)
        assertEquals(7, target.lineNumber)
        assertNull(SpockDefinitionLookup.find(module, content, Position(9, 20)))
        assertNull(SpockDefinitionLookup.find(module, content, Position(2, 24)))
        assertEquals(
            7,
            SpockDefinitionLookup.find(module, content, Position(4, 10), module.findNodeAt(4, 10))?.lineNumber,
        )
    }
}
