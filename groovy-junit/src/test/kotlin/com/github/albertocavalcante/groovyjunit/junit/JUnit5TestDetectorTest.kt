package com.github.albertocavalcante.groovyjunit.junit

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class JUnit5TestDetectorTest {

    private val detector = JUnit5TestDetector()
    private val parser = GroovyParserFacade()

    private fun parse(source: String, className: String): Pair<ClassNode, ModuleNode?> {
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Test.groovy"),
                content = source,
                compilePhase = Phases.CONVERSION,
            ),
        )
        val module = result.ast as? ModuleNode
        val classNode = module?.classes?.find { it.name == className }
            ?: throw IllegalArgumentException("Class $className not found in source")
        return classNode to module
    }

    @Test
    fun `framework is JUNIT5`() {
        assertEquals(TestFramework.JUNIT5, detector.framework)
    }

    @Test
    fun `appliesTo returns true when module has JUnit import`() {
        val source = """
            import org.junit.jupiter.api.Test
            class MyTest {}
        """.trimIndent()
        val (classNode, module) = parse(source, "MyTest")

        assertTrue(detector.appliesTo(classNode, module))
    }

    @Test
    fun `appliesTo returns true when module has JUnit star import`() {
        val source = """
            import org.junit.jupiter.api.*
            class MyTest {}
        """.trimIndent()
        val (classNode, module) = parse(source, "MyTest")

        assertTrue(detector.appliesTo(classNode, module))
    }

    @Test
    fun `appliesTo returns true when method has @Test annotation`() {
        val source = """
            class MyTest {
                @org.junit.jupiter.api.Test
                void myTest() {}
            }
        """.trimIndent()
        val (classNode, module) = parse(source, "MyTest")

        assertTrue(detector.appliesTo(classNode, module))
    }

    @Test
    fun `appliesTo returns true when method has simple @Test annotation`() {
        val source = """
            class MyTest {
                @Test
                void myTest() {}
            }
        """.trimIndent()
        val (classNode, module) = parse(source, "MyTest")

        assertTrue(detector.appliesTo(classNode, module))
    }

    @Test
    fun `appliesTo returns true when class has @Nested annotation`() {
        val source = """
            class Outer {
                @org.junit.jupiter.api.Nested
                class Inner {}
            }
        """.trimIndent()
        // Note: GroovyParserFacade parses module, which contains classes.
        // We need to find the Inner class.
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Test.groovy"),
                content = source,
                compilePhase = Phases.CONVERSION,
            ),
        )
        val module = result.ast as ModuleNode
        val innerClass = module.classes.find { it.name.endsWith("Inner") }!!

        assertTrue(detector.appliesTo(innerClass, module))
    }

    @Test
    fun `appliesTo returns false for non-test class`() {
        val source = """
            class MyService {
                void doSomething() {}
            }
        """.trimIndent()
        val (classNode, module) = parse(source, "MyService")

        assertFalse(detector.appliesTo(classNode, module))
    }

    @Test
    fun `extractTests finds class and test methods`() {
        val source = """
            import org.junit.jupiter.api.Test
            class MyTest {
                @Test
                void testOne() {}

                @org.junit.jupiter.api.RepeatedTest
                void testRepeated() {}

                void helper() {}
            }
        """.trimIndent()
        val (classNode, _) = parse(source, "MyTest")

        val tests = detector.extractTests(classNode)

        assertEquals(3, tests.size) // Class + 2 methods

        // Class item
        val classItem = tests.find { it.kind == TestItemKind.CLASS }
        assertEquals("MyTest", classItem?.id)
        assertEquals(TestFramework.JUNIT5, classItem?.framework)

        // Method items
        val methodItems = tests.filter { it.kind == TestItemKind.METHOD }
        assertEquals(2, methodItems.size)

        assertTrue(methodItems.any { it.name == "testOne" })
        assertTrue(methodItems.any { it.name == "testRepeated" })
    }

    @Test
    fun `should detect ParameterizedTest with specific import`() {
        val source = """
            import org.junit.jupiter.params.ParameterizedTest
            import org.junit.jupiter.params.provider.ValueSource

            class ParameterizedTests {
                @ParameterizedTest
                @ValueSource(strings = ["foo", "bar"])
                void test(String value) {}
            }
        """.trimIndent()
        val (classNode, module) = parse(source, "ParameterizedTests")

        assertTrue(detector.appliesTo(classNode, module))
    }
}
