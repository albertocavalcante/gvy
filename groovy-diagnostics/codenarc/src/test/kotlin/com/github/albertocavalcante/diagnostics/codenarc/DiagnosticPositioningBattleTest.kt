package com.github.albertocavalcante.diagnostics.codenarc

import com.github.albertocavalcante.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.diagnostics.api.WorkspaceContext
import io.mockk.every
import io.mockk.mockk
import org.codenarc.results.Results
import org.codenarc.rule.Rule
import org.codenarc.rule.Violation
import org.eclipse.lsp4j.Diagnostic
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * Battle Test: End-to-end diagnostic positioning tests.
 *
 * These tests verify that diagnostics are positioned correctly in real-world scenarios
 * with actual source code and CodeNarc violations.
 *
 * This test suite simulates the full diagnostic flow:
 * 1. Source code with violations
 * 2. CodeNarc analysis (mocked Results)
 * 3. Conversion to LSP Diagnostics
 * 4. Verification of exact Range positions
 */
class DiagnosticPositioningBattleTest {

    @Test
    fun `should position TrailingWhitespace diagnostic at end of line`() {
        val sourceCode = """
            class Test {
                def method() {
                    return 1
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "TrailingWhitespace",
            message = "Line ends with whitespace characters",
            lineNumber = 2, // "    def method() {   " (with trailing spaces)
            sourceLine = "    def method() {   ",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 2 (0-based: line 1): "    def method() {   "
        // Trailing whitespace starts at position 18 (after '}')
        assertEquals(1, diag.range.start.line)
        assertEquals(18, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(21, diag.range.end.character) // 3 trailing spaces
    }

    @Test
    fun `should position UnnecessarySemicolon diagnostic on semicolon character`() {
        val sourceCode = """
            class Test {
                def x = 1;
                def y = 2
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "UnnecessarySemicolon",
            message = "Semicolon at end of line is unnecessary",
            lineNumber = 2, // "    def x = 1;"
            sourceLine = "    def x = 1;",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 2 (0-based: line 1): "    def x = 1;"
        // Semicolon is at position 13
        assertEquals(1, diag.range.start.line)
        assertEquals(13, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(14, diag.range.end.character)
    }

    @Test
    fun `should position UnusedVariable diagnostic on variable name`() {
        val sourceCode = """
            class Test {
                def method() {
                    def unusedVar = 123
                    return 1
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "UnusedVariable",
            message = "The variable [unusedVar] in class Test is not used",
            lineNumber = 3, // "        def unusedVar = 123"
            sourceLine = "        def unusedVar = 123",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 3 (0-based: line 2): "        def unusedVar = 123"
        // "unusedVar" starts at position 12
        assertEquals(2, diag.range.start.line)
        assertEquals(12, diag.range.start.character)
        assertEquals(2, diag.range.end.line)
        assertEquals(21, diag.range.end.character) // "unusedVar" is 9 characters
    }

    @Test
    fun `should position ClassName diagnostic on class name`() {
        val sourceCode = """
            class myClass {
                def method() {
                    return 1
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "ClassName",
            message = "The class name [myClass] does not match the pattern",
            lineNumber = 1, // "class myClass {"
            sourceLine = "class myClass {",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 1 (0-based: line 0): "class myClass {"
        // "myClass" starts at position 6
        assertEquals(0, diag.range.start.line)
        assertEquals(6, diag.range.start.character)
        assertEquals(0, diag.range.end.line)
        assertEquals(13, diag.range.end.character) // "myClass" is 7 characters
    }

    @Test
    fun `should position MethodName diagnostic on method name`() {
        val sourceCode = """
            class Test {
                def MyMethod() {
                    return 1
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "MethodName",
            message = "The method name [MyMethod] does not match the pattern",
            lineNumber = 2, // "    def MyMethod() {"
            sourceLine = "    def MyMethod() {",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 2 (0-based: line 1): "    def MyMethod() {"
        // "MyMethod" starts at position 8
        assertEquals(1, diag.range.start.line)
        assertEquals(8, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(16, diag.range.end.character) // "MyMethod" is 8 characters
    }

    @Test
    fun `should position SpaceAfterComma diagnostic on comma`() {
        val sourceCode = """
            class Test {
                def list = [1,2, 3]
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "SpaceAfterComma",
            message = "The comma should be followed by a space",
            lineNumber = 2, // "    def list = [1,2, 3]"
            sourceLine = "    def list = [1,2, 3]",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 2 (0-based: line 1): "    def list = [1,2, 3]"
        // First comma (without space) is at position 17
        assertEquals(1, diag.range.start.line)
        assertEquals(17, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(18, diag.range.end.character)
    }

    @Test
    fun `should position Indentation diagnostic at start of line`() {
        val sourceCode = """
            class Test {
               def method() {
                    return 1
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "Indentation",
            message = "Incorrect indentation: expected 4, was 3",
            lineNumber = 2, // "   def method() {" (3 spaces)
            sourceLine = "   def method() {",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 2 (0-based: line 1): "   def method() {"
        // Should highlight the indentation (columns 0-3)
        assertEquals(1, diag.range.start.line)
        assertEquals(0, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(3, diag.range.end.character)
    }

    @Test
    fun `should position CatchException diagnostic on Exception type`() {
        val sourceCode = """
            class Test {
                def method() {
                    try {
                        risky()
                    } catch (Exception e) {
                        handle(e)
                    }
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "CatchException",
            message = "Catching Exception is too broad",
            lineNumber = 5, // "    } catch (Exception e) {"
            sourceLine = "    } catch (Exception e) {",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 5 (0-based: line 4): "    } catch (Exception e) {"
        // "Exception" starts at position 13
        assertEquals(4, diag.range.start.line)
        assertEquals(13, diag.range.start.character)
        assertEquals(4, diag.range.end.line)
        assertEquals(22, diag.range.end.character) // "Exception" is 9 characters
    }

    @Test
    fun `should handle multiple violations on different lines`() {
        val sourceCode = """
            class Test {
                def x = 1;
                def y = 2;
            }
        """.trimIndent()

        val violations = listOf(
            createViolation(
                ruleName = "UnnecessarySemicolon",
                message = "Semicolon at end of line is unnecessary",
                lineNumber = 2,
                sourceLine = "    def x = 1;",
            ),
            createViolation(
                ruleName = "UnnecessarySemicolon",
                message = "Semicolon at end of line is unnecessary",
                lineNumber = 3,
                sourceLine = "    def y = 2;",
            ),
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, violations)

        assertEquals(2, diagnostics.size)

        // First semicolon on line 2
        val diag1 = diagnostics[0]
        assertEquals(1, diag1.range.start.line)
        assertEquals(13, diag1.range.start.character)

        // Second semicolon on line 3
        val diag2 = diagnostics[1]
        assertEquals(2, diag2.range.start.line)
        assertEquals(13, diag2.range.start.character)
    }

    @Test
    fun `should handle empty line violations`() {
        val sourceCode = """
            class Test {

                def method() {
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "ClassStartsWithBlankLine",
            message = "Class starts with a blank line",
            lineNumber = 2, // Empty line
            sourceLine = "",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Empty line should have (0, 0) range
        assertEquals(1, diag.range.start.line)
        assertEquals(0, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(0, diag.range.end.character)
    }

    @Test
    fun `should handle violations at end of file`() {
        val sourceCode = """class Test {
    def method() {
        return 1
    }
}   """ // Line 5 has trailing spaces after }

        val violation = createViolation(
            ruleName = "TrailingWhitespace",
            message = "Line ends with whitespace characters",
            lineNumber = 5, // Last line (1-based)
            sourceLine = "}   ",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 5 (0-based: line 4): "}   "
        // Trailing whitespace starts at position 1 (after '}')
        assertEquals(4, diag.range.start.line)
        assertEquals(1, diag.range.start.character)
        assertEquals(4, diag.range.end.line)
        assertEquals(4, diag.range.end.character) // 3 trailing spaces
    }

    @Test
    fun `should handle UnusedImport diagnostic on entire import line`() {
        val sourceCode = """
            import java.util.ArrayList

            class Test {
                def method() {
                    return 1
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "UnusedImport",
            message = "The import java.util.ArrayList is unused",
            lineNumber = 1,
            sourceLine = "import java.util.ArrayList",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 1 (0-based: line 0): "import java.util.ArrayList"
        // Should highlight from "import" to end of line
        assertEquals(0, diag.range.start.line)
        assertEquals(0, diag.range.start.character)
        assertEquals(0, diag.range.end.line)
        assertEquals(26, diag.range.end.character)
    }

    @Test
    fun `should handle UnnecessaryPublicModifier diagnostic on public keyword`() {
        val sourceCode = """
            class Test {
                public def method() {
                    return 1
                }
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "UnnecessaryPublicModifier",
            message = "The public modifier is unnecessary for methods",
            lineNumber = 2,
            sourceLine = "    public def method() {",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 2 (0-based: line 1): "    public def method() {"
        // "public" keyword at position 4
        assertEquals(1, diag.range.start.line)
        assertEquals(4, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(10, diag.range.end.character) // "public" is 6 characters
    }

    @Test
    fun `should handle UnnecessaryDefInVariableDeclaration diagnostic on def keyword`() {
        val sourceCode = """
            class Test {
                def String name = "test"
            }
        """.trimIndent()

        val violation = createViolation(
            ruleName = "UnnecessaryDefInVariableDeclaration",
            message = "The def keyword is unnecessary when a type is specified",
            lineNumber = 2,
            sourceLine = "    def String name = \"test\"",
        )

        val diagnostics = analyzAndGetDiagnostics(sourceCode, listOf(violation))

        assertEquals(1, diagnostics.size)
        val diag = diagnostics[0]

        // Line 2 (0-based: line 1): "    def String name = "test""
        // "def" keyword at position 4
        assertEquals(1, diag.range.start.line)
        assertEquals(4, diag.range.start.character)
        assertEquals(1, diag.range.end.line)
        assertEquals(7, diag.range.end.character) // "def" is 3 characters
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private fun createViolation(ruleName: String, message: String, lineNumber: Int, sourceLine: String): Violation {
        val rule = mockk<Rule>()
        every { rule.name } returns ruleName
        every { rule.priority } returns 2

        val violation = mockk<Violation>()
        every { violation.rule } returns rule
        every { violation.message } returns message
        every { violation.lineNumber } returns lineNumber
        every { violation.sourceLine } returns sourceLine

        return violation
    }

    private fun createStubAnalyzer(results: Results): CodeAnalyzer = object : CodeAnalyzer {
        override fun analyze(
            sourceCode: String,
            fileName: String,
            rulesetContent: String,
            propertiesFile: String?,
        ): Results = results
    }

    private fun createWorkspaceContext(): WorkspaceContext = object : WorkspaceContext {
        override val root: Path? = Paths.get(".")
        override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
            override val isEnabled: Boolean = true
            override val propertiesFile: String? = null
            override val autoDetectConfig: Boolean = false
        }
    }

    private fun analyzAndGetDiagnostics(sourceCode: String, violations: List<Violation>): List<Diagnostic> {
        val leafResults = mockk<Results>()
        every { leafResults.children } returns mutableListOf()
        every { leafResults.violations } returns violations

        val workspaceContext = createWorkspaceContext()
        val diagnosticProvider = CodeNarcDiagnosticProvider(
            workspaceContext = workspaceContext,
            codeAnalyzer = createStubAnalyzer(leafResults),
        )

        return diagnosticProvider.analyzeAndGetDiagnostics(sourceCode, "Test.groovy")
    }
}
