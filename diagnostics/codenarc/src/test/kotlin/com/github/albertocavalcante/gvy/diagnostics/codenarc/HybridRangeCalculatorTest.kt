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

/**
 * Tests for HybridRangeCalculator - delegates to AST or heuristic based on rule support.
 *
 * The hybrid approach ensures:
 * - AST-supported rules use precise AST-based positioning when AST is available
 * - AST-supported rules fall back to heuristics when AST is unavailable
 * - Non-AST rules always use heuristic positioning
 */
class HybridRangeCalculatorTest {

    // ==========================================
    // AST PATH TESTS (AST available + supported rule)
    // ==========================================

    @Test
    fun `uses AST path for ClassName when AST available`() {
        val code = """
            public abstract class AbstractService {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = HybridRangeCalculator(module)
        val sourceLine = code.lines()[0]
        val violation =
            createViolation(
                "ClassName",
                "Class name [AbstractService] does not match",
                line = 1,
                sourceLine = sourceLine,
            )

        val range = calculator.calculateRange(violation, sourceLine)

        // AST path should find "AbstractService" precisely despite modifiers
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("AbstractService", highlighted)
    }

    @Test
    fun `uses AST path for MethodName when AST available`() {
        val code = """
            class MyClass {
                public static void myMethod() {
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = HybridRangeCalculator(module)
        val sourceLine = code.lines()[1]
        val violation =
            createViolation("MethodName", "Method name [myMethod] does not match", line = 2, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation, sourceLine)

        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("myMethod", highlighted)
    }

    @Test
    fun `uses AST path for FieldName when AST available`() {
        val code = """
            class MyClass {
                private String myField = "test"
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = HybridRangeCalculator(module)
        val sourceLine = code.lines()[1]
        val violation =
            createViolation("FieldName", "Field name [myField] does not match", line = 2, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation, sourceLine)

        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("myField", highlighted)
    }

    @Test
    fun `uses AST path for UnusedVariable when AST available`() {
        val code = """
            class MyClass {
                void myMethod() {
                    String unusedVar = "test"
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = HybridRangeCalculator(module)
        val sourceLine = code.lines()[2]
        val violation = createViolation(
            "UnusedVariable",
            "The variable [unusedVar] in class MyClass is not used",
            line = 3,
            sourceLine = sourceLine,
        )

        val range = calculator.calculateRange(violation, sourceLine)

        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("unusedVar", highlighted)
    }

    @Test
    fun `uses AST path for UnusedImport when AST available`() {
        val code = """
            import java.util.List

            class MyClass {
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val calculator = HybridRangeCalculator(module)
        val sourceLine = code.lines()[0]
        val violation = createViolation(
            "UnusedImport",
            "The import [java.util.List] is not used",
            line = 1,
            sourceLine = sourceLine,
        )

        val range = calculator.calculateRange(violation, sourceLine)

        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("List", highlighted)
    }

    // ==========================================
    // HEURISTIC FALLBACK TESTS (AST unavailable)
    // ==========================================

    @Test
    fun `falls back to heuristic for ClassName when AST unavailable`() {
        val calculator = HybridRangeCalculator(null) // No AST
        val sourceLine = "class MyClass {"
        val violation = createViolation(
            "ClassName",
            "The class name [MyClass] does not match",
            line = 1,
            sourceLine = sourceLine,
        )

        val range = calculator.calculateRange(violation, sourceLine)

        // Heuristic should still find "MyClass" from the message
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("MyClass", highlighted)
    }

    @Test
    fun `falls back to heuristic for UnusedVariable when AST unavailable`() {
        val calculator = HybridRangeCalculator(null) // No AST
        val sourceLine = "    String unusedVar = \"test\""
        val violation = createViolation(
            "UnusedVariable",
            "The variable [unusedVar] in class MyClass is not used",
            line = 3,
            sourceLine = sourceLine,
        )

        val range = calculator.calculateRange(violation, sourceLine)

        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("unusedVar", highlighted)
    }

    // ==========================================
    // NON-AST RULES (always use heuristic)
    // ==========================================

    @Test
    fun `uses heuristic for UnnecessarySemicolon rule`() {
        val module = parseToModuleNode("class X {}")
        val calculator = HybridRangeCalculator(module)
        val sourceLine = "def x = 1;"
        val violation =
            createViolation("UnnecessarySemicolon", "Unnecessary semicolon", line = 1, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation, sourceLine)

        // Should highlight the semicolon
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals(";", highlighted)
    }

    @Test
    fun `uses heuristic for TrailingWhitespace rule`() {
        val module = parseToModuleNode("class X {}")
        val calculator = HybridRangeCalculator(module)
        val sourceLine = "def x = 1   "
        val violation = createViolation("TrailingWhitespace", "Trailing whitespace", line = 1, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation, sourceLine)

        // Should highlight from end of content to end of line
        assertEquals(9, range.first) // "def x = 1" is 9 chars
        assertEquals(12, range.second) // total length is 12
    }

    @Test
    fun `uses heuristic for Indentation rule`() {
        val module = parseToModuleNode("class X {}")
        val calculator = HybridRangeCalculator(module)
        val sourceLine = "    def x = 1" // 4 spaces indentation
        val violation = createViolation("Indentation", "Wrong indentation", line = 1, sourceLine = sourceLine)

        val range = calculator.calculateRange(violation, sourceLine)

        // Should highlight indentation
        assertEquals(0, range.first)
        assertEquals(4, range.second)
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
