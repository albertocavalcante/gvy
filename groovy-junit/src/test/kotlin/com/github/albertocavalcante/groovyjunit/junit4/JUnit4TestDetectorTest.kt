package com.github.albertocavalcante.groovyjunit.junit4

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovytesting.api.TestFramework
import com.github.albertocavalcante.groovytesting.api.TestItemKind
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class JUnit4TestDetectorTest {

    private val detector = JUnit4TestDetector()
    private val parser = GroovyParserFacade()

    private fun parse(source: String, className: String): Pair<org.codehaus.groovy.ast.ClassNode, ModuleNode> {
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///Test.groovy"),
                content = source,
                compilePhase = Phases.CONVERSION,
            ),
        )
        val module = result.ast as ModuleNode
        val classNode = module.classes.find { it.name == className }
            ?: throw IllegalArgumentException("Class $className not found in source")
        return classNode to module
    }

    @Test
    fun `framework is JUNIT4`() {
        assertEquals(TestFramework.JUNIT4, detector.framework)
    }

    @Test
    fun `appliesTo returns true for @Test annotation imports`() {
        val source = """
            import org.junit.Test
            class MyTest {}
        """.trimIndent()
        val (classNode, module) = parse(source, "MyTest")

        assertTrue(detector.appliesTo(classNode, module))
    }

    @Test
    fun `appliesTo returns true for TestCase import`() {
        val source = """
            import junit.framework.TestCase
            class MyLegacyTest extends TestCase {}
        """.trimIndent()
        val (classNode, module) = parse(source, "MyLegacyTest")

        assertTrue(detector.appliesTo(classNode, module))
    }

    @Test
    fun `appliesTo returns true for extends TestCase`() {
        val source = """
            class MyLegacyTest extends junit.framework.TestCase {}
        """.trimIndent()
        val (classNode, module) = parse(source, "MyLegacyTest")

        assertTrue(detector.appliesTo(classNode, module))
    }

    @Test
    fun `extractTests finds @Test annotated methods`() {
        val source = """
            import org.junit.Test
            class MyTest {
                @Test
                void testSomething() {}

                void helper() {}
            }
        """.trimIndent()
        val (classNode, _) = parse(source, "MyTest")

        val tests = detector.extractTests(classNode)
        assertEquals(2, tests.size) // Class + 1 method

        val methodItem = tests.find { it.kind == TestItemKind.METHOD }!!
        assertEquals("testSomething", methodItem.name)
        assertEquals(TestFramework.JUNIT4, methodItem.framework)
    }

    @Test
    fun `extractTests finds legacy test methods starting with 'test'`() {
        val source = """
            import junit.framework.TestCase
            class MyLegacyTest extends TestCase {
                void testVintage() {}
                void helperTest() {} // Should be ignored
                public void testPublic() {}
                public void testWithParam(String s) {} // Should be ignored
            }
        """.trimIndent()
        val (classNode, _) = parse(source, "MyLegacyTest")

        val tests = detector.extractTests(classNode)
        val methods = tests.filter { it.kind == TestItemKind.METHOD }

        assertEquals(2, methods.size, "Should find exactly 2 test methods")
        assertTrue(methods.any { it.name == "testVintage" }, "Should find testVintage")
        assertTrue(methods.any { it.name == "testPublic" }, "Should find testPublic")
        assertFalse(methods.any { it.name == "helperTest" }, "Should NOT find helperTest")
        assertFalse(methods.any { it.name == "testWithParam" }, "Should NOT find testWithParam")
    }

    @Test
    fun `appliesTo returns true for @RunWith annotation`() {
        val source = """
            import org.junit.runner.RunWith
            @RunWith(org.junit.runners.Parameterized)
            class MyParameterizedTest {}
        """.trimIndent()
        val (classNode, module) = parse(source, "MyParameterizedTest")

        assertTrue(detector.appliesTo(classNode, module))
    }
}
