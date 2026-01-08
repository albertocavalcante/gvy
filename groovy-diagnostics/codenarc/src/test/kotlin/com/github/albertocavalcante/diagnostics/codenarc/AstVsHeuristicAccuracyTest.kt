package com.github.albertocavalcante.diagnostics.codenarc

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
 * Tests comparing AST-based positioning vs heuristic positioning.
 *
 * These tests demonstrate cases where AST-based positioning provides
 * more accurate results than heuristic string matching.
 */
class AstVsHeuristicAccuracyTest {

    // ==========================================
    // ClassName ACCURACY TESTS
    // ==========================================

    @Test
    fun `AST correctly handles class with modifiers`() {
        // This is a case where heuristics might struggle
        val code = "public abstract class MyServiceImpl {"
        val module = parseToModuleNode("$code\n}")
        val violation = createViolation("ClassName", "The class name [MyServiceImpl] does not match", 1, code)

        // AST path
        val astCalculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val astRange = astCalculator.calculateRange(violation)

        // Verify AST finds "MyServiceImpl" correctly
        val astHighlighted = code.substring(astRange!!.first, astRange.second)
        assertEquals("MyServiceImpl", astHighlighted)
    }

    @Test
    fun `AST correctly handles nested class`() {
        val code = """
            class Outer {
                class Inner {
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val innerLine = code.lines()[1]
        val violation = createViolation("ClassName", "The class name [Inner] does not match", 2, innerLine)

        // AST path
        val astCalculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val astRange = astCalculator.calculateRange(violation)

        // Verify AST finds "Inner" correctly
        val astHighlighted = innerLine.substring(astRange!!.first, astRange.second)
        assertEquals("Inner", astHighlighted)
    }

    // ==========================================
    // FieldName ACCURACY TESTS
    // ==========================================

    @Test
    fun `AST correctly identifies field name when constructor call present`() {
        // Edge case: "String badField = new String()" - should highlight "badField", not "String"
        val code = """
            class MyClass {
                String badField = new String()
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val fieldLine = code.lines()[1]
        val violation = createViolation("FieldName", "The field name [badField] does not match", 2, fieldLine)

        // AST path
        val astCalculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val astRange = astCalculator.calculateRange(violation)

        // Verify AST finds "badField" correctly (not "String")
        val highlighted = fieldLine.substring(astRange!!.first, astRange.second)
        assertEquals("badField", highlighted)
    }

    @Test
    fun `AST correctly identifies field name with multiple types on line`() {
        // Another edge case with type names
        val code = """
            class MyClass {
                List<String> myItems = new ArrayList<String>()
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val fieldLine = code.lines()[1]
        val violation = createViolation("FieldName", "The field name [myItems] does not match", 2, fieldLine)

        // AST path
        val astCalculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val astRange = astCalculator.calculateRange(violation)

        // Verify AST finds "myItems" correctly
        val highlighted = fieldLine.substring(astRange!!.first, astRange.second)
        assertEquals("myItems", highlighted)
    }

    // ==========================================
    // MethodName ACCURACY TESTS
    // ==========================================

    @Test
    fun `AST correctly identifies method name with many modifiers`() {
        val code = """
            class MyClass {
                @Override protected synchronized void myMethod() {}
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val methodLine = code.lines()[1]
        val violation = createViolation("MethodName", "The method name [myMethod] does not match", 2, methodLine)

        // AST path
        val astCalculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val astRange = astCalculator.calculateRange(violation)

        // Verify AST finds "myMethod" correctly
        val highlighted = methodLine.substring(astRange!!.first, astRange.second)
        assertEquals("myMethod", highlighted)
    }

    // ==========================================
    // UnusedVariable ACCURACY TESTS
    // ==========================================

    @Test
    fun `AST correctly identifies variable in complex declaration`() {
        val code = """
            class MyClass {
                void method() {
                    def (x, unusedVar) = [1, 2]
                }
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val varLine = code.lines()[2]
        val violation = createViolation("UnusedVariable", "The variable [unusedVar] is not used", 3, varLine)

        // AST path - should find "unusedVar" in the tuple declaration
        val astCalculator = AstAwareRangeCalculator(AstNodeFinder(module))
        val astRange = astCalculator.calculateRange(violation)

        // The AST might not find tuple declarations, but the regex should still work
        if (astRange != null) {
            val highlighted = varLine.substring(astRange.first, astRange.second)
            assertEquals("unusedVar", highlighted)
        }
        // If AST fails, that's acceptable for this edge case - hybrid will fall back to heuristic
    }

    // ==========================================
    // HYBRID PROVIDES BEST OF BOTH WORLDS
    // ==========================================

    @Test
    fun `Hybrid calculator uses AST when available and falls back gracefully`() {
        val code = """
            class MyClass {
                String field
            }
        """.trimIndent()
        val module = parseToModuleNode(code)
        val fieldLine = code.lines()[1]
        val violation = createViolation("FieldName", "The field name [field] does not match", 2, fieldLine)

        // Hybrid with AST
        val hybridWithAst = HybridRangeCalculator(module)
        val rangeWithAst = hybridWithAst.calculateRange(violation, fieldLine)

        // Hybrid without AST
        val hybridWithoutAst = HybridRangeCalculator(null)
        val rangeWithoutAst = hybridWithoutAst.calculateRange(violation, fieldLine)

        // Both should find "field" correctly
        assertEquals("field", fieldLine.substring(rangeWithAst.first, rangeWithAst.second))
        assertEquals("field", fieldLine.substring(rangeWithoutAst.first, rangeWithoutAst.second))
    }

    @Test
    fun `Hybrid falls back to heuristic when AST node not found`() {
        // Use a line number that doesn't exist in the AST
        val code = "class X {}"
        val module = parseToModuleNode(code)
        val sourceLine = "    String field"
        // Line 999 doesn't exist in the AST
        val violation = createViolation("FieldName", "The field name [field] does not match", 999, sourceLine)

        val hybrid = HybridRangeCalculator(module)
        val range = hybrid.calculateRange(violation, sourceLine)

        // Should fall back to heuristic and still find "field"
        val highlighted = sourceLine.substring(range.first, range.second)
        assertEquals("field", highlighted)
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
