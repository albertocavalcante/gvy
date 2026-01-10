package com.github.albertocavalcante.groovytesting.registry

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import com.github.albertocavalcante.groovytesting.spock.SpockTestDetector
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

class TestFrameworkRegistryTest {

    private val parser = GroovyParserFacade()
    private lateinit var registry: TestFrameworkRegistry

    @BeforeEach
    fun setUp() {
        // Use isolated instance for each test to avoid interference
        registry = TestFrameworkRegistry()
    }

    @AfterEach
    fun tearDown() {
        // No cleanup needed - each test has its own registry instance
    }

    @Test
    fun `should register and retrieve detectors`() {
        val spockDetector = SpockTestDetector()
        registry.register(spockDetector)

        val detectors = registry.getDetectors()
        assertEquals(1, detectors.size)
        assertEquals(TestFramework.SPOCK, detectors[0].framework)
    }

    @Test
    fun `should find detector by framework`() {
        registry.register(SpockTestDetector())

        val found = registry.getDetector(TestFramework.SPOCK)
        assertNotNull(found)
        assertEquals(TestFramework.SPOCK, found?.framework)

        val notFound = registry.getDetector(TestFramework.JUNIT5)
        assertNull(notFound)
    }

    @Test
    fun `should detect Spock specification`() {
        registry.register(SpockTestDetector())

        val source = """
            import spock.lang.Specification

            class MySpec extends Specification {
                def "should work"() {
                    expect: true
                }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///MySpec.groovy"),
                content = source,
                compilePhase = Phases.CONVERSION,
            ),
        )

        val classNode = result.ast?.classes?.find { it.name == "MySpec" }
        assertNotNull(classNode)

        val detector = registry.findDetector(classNode!!, result.ast)
        assertNotNull(detector)
        assertEquals(TestFramework.SPOCK, detector?.framework)
    }

    @Test
    fun `should extract tests from Spock specification`() {
        registry.register(SpockTestDetector())

        val source = """
            import spock.lang.Specification

            class CalculatorSpec extends Specification {
                def "should add numbers"() {
                    expect: 1 + 1 == 2
                }

                def "should subtract numbers"() {
                    expect: 3 - 1 == 2
                }
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///CalculatorSpec.groovy"),
                content = source,
                compilePhase = Phases.CONVERSION,
            ),
        )

        val classNode = result.ast?.classes?.find { it.name == "CalculatorSpec" }
        assertNotNull(classNode)

        val tests = registry.extractTests(classNode!!, result.ast)

        // 1 class + 2 methods = 3 items
        assertEquals(3, tests.size)

        val classItem = tests.find { it.kind == TestItemKind.CLASS }
        assertNotNull(classItem)
        assertEquals("CalculatorSpec", classItem?.name)

        val methods = tests.filter { it.kind == TestItemKind.METHOD }
        assertEquals(2, methods.size)
        assertTrue(methods.any { it.name == "should add numbers" })
        assertTrue(methods.any { it.name == "should subtract numbers" })
    }

    @Test
    fun `should return empty for non-test class`() {
        registry.register(SpockTestDetector())

        val source = """
            class RegularClass {
                def method() {}
            }
        """.trimIndent()

        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///RegularClass.groovy"),
                content = source,
                compilePhase = Phases.CONVERSION,
            ),
        )

        val classNode = result.ast?.classes?.find { it.name == "RegularClass" }
        assertNotNull(classNode)

        val tests = registry.extractTests(classNode!!, result.ast)
        assertTrue(tests.isEmpty())
    }
}
