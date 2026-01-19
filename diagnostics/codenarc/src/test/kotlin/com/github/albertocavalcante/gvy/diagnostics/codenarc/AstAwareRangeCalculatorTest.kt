package com.github.albertocavalcante.gvy.diagnostics.codenarc

import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for AstAwareRangeCalculator - extracts precise ranges from AST nodes.
 *
 * These tests verify that AST-based positioning produces accurate column ranges
 * for diagnostic highlighting, replacing heuristic string matching.
 */
class AstAwareRangeCalculatorTest {

    // ==========================================
    // ClassName TESTS
    // ==========================================

    @Test
    fun `ClassName rule returns range for class name`() {
        val code = """
            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val sourceLine = code.lines()[0]
        val violation =
            createViolation("ClassName", "Class name should start with uppercase", line = 1, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation)

        assertNotNull(range, "Should calculate range for ClassName")
        // Verify the range highlights "MyClass" (0-based columns)
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("MyClass", highlighted)
    }

    @Test
    fun `ClassName rule with modifiers returns correct range`() {
        val code = """
            public abstract class AbstractService {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val sourceLine = code.lines()[0]
        val violation =
            createViolation("ClassName", "Class name should start with uppercase", line = 1, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation)

        assertNotNull(range, "Should calculate range for ClassName with modifiers")
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("AbstractService", highlighted)
    }

    // ==========================================
    // MethodName TESTS
    // ==========================================

    @Test
    fun `MethodName rule returns range for method name`() {
        val code = """
            class MyClass {
                void badMethodName() {
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val sourceLine = code.lines()[1]
        val violation =
            createViolation("MethodName", "Method name should start with lowercase", line = 2, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation)

        assertNotNull(range, "Should calculate range for MethodName")
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("badMethodName", highlighted)
    }

    // ==========================================
    // FieldName TESTS
    // ==========================================

    @Test
    fun `FieldName rule returns range for field name`() {
        val code = """
            class MyClass {
                String BadFieldName
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val sourceLine = code.lines()[1]
        val violation =
            createViolation("FieldName", "Field name should start with lowercase", line = 2, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation)

        assertNotNull(range, "Should calculate range for FieldName")
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("BadFieldName", highlighted)
    }

    // ==========================================
    // UnusedVariable TESTS
    // ==========================================

    @Test
    fun `UnusedVariable rule returns range for variable name`() {
        val code = """
            class MyClass {
                void myMethod() {
                    String unusedVar = "test"
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val sourceLine = code.lines()[2]
        val violation = createViolation(
            "UnusedVariable",
            "The variable [unusedVar] in class MyClass is not used",
            line = 3,
            sourceLine = sourceLine,
        )

        val range = calculator.calculateRange(violation)

        assertNotNull(range, "Should calculate range for UnusedVariable")
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("unusedVar", highlighted)
    }

    // ==========================================
    // UnusedImport TESTS
    // ==========================================

    @Test
    fun `UnusedImport rule returns range for import`() {
        val code = """
            import java.util.List

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val sourceLine = code.lines()[0]
        val violation = createViolation(
            "UnusedImport",
            "The import [java.util.List] is not used",
            line = 1,
            sourceLine = sourceLine,
        )

        val range = calculator.calculateRange(violation)

        assertNotNull(range, "Should calculate range for UnusedImport")
        val highlighted = sourceLine.substring(range.first, range.second)
        // Should highlight just the simple class name "List"
        assertEquals("List", highlighted)
    }

    // ==========================================
    // UNSUPPORTED RULES
    // ==========================================

    @Test
    fun `unsupported rule returns null`() {
        val code = """
            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val violation = createViolation("TrailingWhitespace", "Trailing whitespace", line = 1)

        val range = calculator.calculateRange(violation)

        assertNull(range, "Should return null for unsupported rule")
    }

    @Test
    fun `isSupported returns true for supported rules`() {
        assert(AstAwareRangeCalculator.isSupported("ClassName"))
        assert(AstAwareRangeCalculator.isSupported("MethodName"))
        assert(AstAwareRangeCalculator.isSupported("FieldName"))
        assert(AstAwareRangeCalculator.isSupported("UnusedVariable"))
        assert(AstAwareRangeCalculator.isSupported("UnusedImport"))
    }

    @Test
    fun `isSupported returns false for unsupported rules`() {
        assert(!AstAwareRangeCalculator.isSupported("TrailingWhitespace"))
        assert(!AstAwareRangeCalculator.isSupported("Indentation"))
        assert(!AstAwareRangeCalculator.isSupported("SpaceAfterComma"))
    }

    // ==========================================
    // HELPER FUNCTIONS
    // ==========================================

    private fun parseToModuleNode(code: String): ModuleNode {
        val config = CompilerConfiguration()
        val compilationUnit = CompilationUnit(config)
        compilationUnit.addSource("TestScript.groovy", code)
        compilationUnit.compile(Phases.CONVERSION)

        val compileUnit = compilationUnit.ast
        val moduleNode = compileUnit?.modules?.firstOrNull()
        return requireNotNull(moduleNode) { "Failed to parse code to AST" }
    }

    private fun createViolation(ruleName: String, message: String, line: Int, sourceLine: String? = null): Violation {
        val rule = mockk<Rule>()
        every { rule.name } returns ruleName
        every { rule.priority } returns 2

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.message } returns message
        every { violation.lineNumber } returns line
        every { violation.sourceLine } returns sourceLine

        return violation
    }
}
