package com.github.albertocavalcante.groovyspock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class SpockFeatureExtractorTest {

    private val parser = GroovyParserFacade()

    /**
     * Parse Groovy source and return class nodes.
     * Uses CONVERSION phase to preserve statement labels (required for Spock block detection).
     */
    private fun parseAndGetClass(source: String, className: String): ClassNode? {
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Test.groovy"),
                content = source,
                compilePhase = Phases.CONVERSION,
            ),
        )
        val module = result.ast as? ModuleNode ?: return null
        return module.classes.find { it.name == className }
    }

    @Test
    fun `should extract feature methods with spock blocks`() {
        val source = """
            import spock.lang.Specification

            class CalculatorSpec extends Specification {
                def "should add two numbers"() {
                    given: "two numbers"
                    def a = 1
                    def b = 2

                    when: "adding them"
                    def result = a + b

                    then: "the result is correct"
                    result == 3
                }

                def "should subtract numbers"() {
                    expect:
                    5 - 3 == 2
                }
            }
        """.trimIndent()

        val classNode = parseAndGetClass(source, "CalculatorSpec")!!

        val features = SpockFeatureExtractor.extractFeatures(classNode)

        assertEquals(2, features.size, "Expected 2 feature methods")
        assertEquals("should add two numbers", features[0].name)
        assertEquals("should subtract numbers", features[1].name)
    }

    @Test
    fun `should exclude setup and cleanup methods`() {
        val source = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def setup() {
                    given:
                    println "setup"
                }

                def cleanup() {
                    given:
                    println "cleanup"
                }

                def "actual test"() {
                    expect:
                    true
                }
            }
        """.trimIndent()

        val classNode = parseAndGetClass(source, "MySpec")!!

        val features = SpockFeatureExtractor.extractFeatures(classNode)

        assertEquals(1, features.size, "Expected only 1 feature method (excluding setup/cleanup)")
        assertEquals("actual test", features[0].name)
    }

    @Test
    fun `should handle spec with no feature methods`() {
        val source = """
            import spock.lang.Specification

            class EmptySpec extends Specification {
                def setup() {
                    println "just setup"
                }
            }
        """.trimIndent()

        val classNode = parseAndGetClass(source, "EmptySpec")!!

        val features = SpockFeatureExtractor.extractFeatures(classNode)

        assertTrue(features.isEmpty())
    }

    @Test
    fun `should capture line numbers`() {
        val source = """
            import spock.lang.Specification

            class LineSpec extends Specification {
                def "first test"() {
                    expect:
                    true
                }

                def "second test"() {
                    expect:
                    false
                }
            }
        """.trimIndent()

        val classNode = parseAndGetClass(source, "LineSpec")!!

        val features = SpockFeatureExtractor.extractFeatures(classNode)

        assertEquals(2, features.size)
        assertTrue(features[0].line > 0, "First feature should have a positive line number")
        assertTrue(features[1].line > features[0].line, "Second feature should be after first")
    }
}
