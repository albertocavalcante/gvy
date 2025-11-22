package com.github.albertocavalcante.groovylsp.dsl

import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive tests for the Diagnostic DSL.
 */
class DiagnosticDslTest {

    @Test
    fun `single diagnostic with basic properties`() {
        val diagnostic = diagnostic {
            range(5, 10, 20)
            error("Syntax error")
            source("test-lsp")
        }

        assertEquals(5, diagnostic.range.start.line)
        assertEquals(10, diagnostic.range.start.character)
        assertEquals(20, diagnostic.range.end.character)
        assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
        assertEquals("Syntax error", diagnostic.message)
        assertEquals("test-lsp", diagnostic.source)
    }

    @Test
    fun `diagnostic with default values`() {
        val diagnostic = diagnostic {
            error("Simple error")
        }

        // Should default to point range at (0,0)
        assertEquals(0, diagnostic.range.start.line)
        assertEquals(0, diagnostic.range.start.character)
        assertEquals(0, diagnostic.range.end.line)
        assertEquals(0, diagnostic.range.end.character)
        assertEquals("groovy-lsp", diagnostic.source)
        assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
    }

    @Test
    fun `diagnostic with different severity levels`() {
        val error = diagnostic { error("Error message") }
        val warning = diagnostic { warning("Warning message") }
        val info = diagnostic { info("Info message") }
        val hint = diagnostic { hint("Hint message") }

        assertEquals(DiagnosticSeverity.Error, error.severity)
        assertEquals(DiagnosticSeverity.Warning, warning.severity)
        assertEquals(DiagnosticSeverity.Information, info.severity)
        assertEquals(DiagnosticSeverity.Hint, hint.severity)

        assertEquals("Error message", error.message)
        assertEquals("Warning message", warning.message)
        assertEquals("Info message", info.message)
        assertEquals("Hint message", hint.message)
    }

    @Test
    fun `diagnostic with point range using at()`() {
        val diagnostic = diagnostic {
            at(10, 5)
            error("Point error")
        }

        assertEquals(10, diagnostic.range.start.line)
        assertEquals(5, diagnostic.range.start.character)
        assertEquals(10, diagnostic.range.end.line)
        assertEquals(5, diagnostic.range.end.character)
    }

    @Test
    fun `diagnostic with line range`() {
        val diagnostic = diagnostic {
            line(7)
            warning("Line warning")
        }

        assertEquals(7, diagnostic.range.start.line)
        assertEquals(0, diagnostic.range.start.character)
        assertEquals(7, diagnostic.range.end.line)
        assertEquals(Int.MAX_VALUE, diagnostic.range.end.character)
    }

    @Test
    fun `diagnostic with multi-line range`() {
        val diagnostic = diagnostic {
            range(startLine = 3, startChar = 5, endLine = 7, endChar = 10)
            error("Multi-line error")
        }

        assertEquals(3, diagnostic.range.start.line)
        assertEquals(5, diagnostic.range.start.character)
        assertEquals(7, diagnostic.range.end.line)
        assertEquals(10, diagnostic.range.end.character)
    }

    @Test
    fun `diagnostic with string and numeric codes`() {
        val stringCode = diagnostic {
            error("Error with string code")
            code("E001")
        }

        val numericCode = diagnostic {
            error("Error with numeric code")
            code(1001)
        }

        assertTrue(stringCode.code!!.isLeft)
        assertEquals("E001", stringCode.code!!.left)

        assertTrue(numericCode.code!!.isRight)
        assertEquals(1001, numericCode.code!!.right)
    }

    @Test
    fun `syntaxError shorthand`() {
        val diagnostic = diagnostic {
            syntaxError(5, "Missing semicolon")
        }

        assertEquals(5, diagnostic.range.start.line)
        assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
        assertEquals("Missing semicolon", diagnostic.message)
        assertEquals("syntax-error", diagnostic.code!!.left)
    }

    @Test
    fun `typeError shorthand`() {
        val diagnostic = diagnostic {
            typeError(3, 10, 20, "Type mismatch")
        }

        assertEquals(3, diagnostic.range.start.line)
        assertEquals(10, diagnostic.range.start.character)
        assertEquals(20, diagnostic.range.end.character)
        assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
        assertEquals("Type mismatch", diagnostic.message)
        assertEquals("type-error", diagnostic.code!!.left)
    }

    @Test
    fun `multiple diagnostics with diagnostics DSL`() {
        val diagnosticList = diagnostics {
            diagnostic {
                range(1, 0, 10)
                error("First error")
            }

            diagnostic {
                range(5, 15, 25)
                warning("Second warning")
            }

            syntaxError(10, "Syntax issue")
            typeError(15, 5, 15, "Type issue")
        }

        assertEquals(4, diagnosticList.size)

        assertEquals(DiagnosticSeverity.Error, diagnosticList[0].severity)
        assertEquals("First error", diagnosticList[0].message)

        assertEquals(DiagnosticSeverity.Warning, diagnosticList[1].severity)
        assertEquals("Second warning", diagnosticList[1].message)

        assertEquals("Syntax issue", diagnosticList[2].message)
        assertEquals("syntax-error", diagnosticList[2].code!!.left)

        assertEquals("Type issue", diagnosticList[3].message)
        assertEquals("type-error", diagnosticList[3].code!!.left)
    }

    @Test
    fun `diagnostics builder with add methods`() {
        val existingDiagnostic = diagnostic {
            error("Existing diagnostic")
        }

        val warning1 = diagnostic { warning("Warning 1") }
        val warning2 = diagnostic { warning("Warning 2") }

        val diagnosticList = diagnostics {
            add(existingDiagnostic)
            addAll(listOf(warning1, warning2))

            diagnostic {
                info("New diagnostic")
            }
        }

        assertEquals(4, diagnosticList.size)
        assertEquals("Existing diagnostic", diagnosticList[0].message)
        assertEquals("Warning 1", diagnosticList[1].message)
        assertEquals("Warning 2", diagnosticList[2].message)
        assertEquals("New diagnostic", diagnosticList[3].message)
    }

    @Test
    fun `DSL is immutable - builder reuse doesn't affect previous results`() {
        val builder = DiagnosticBuilder()

        builder.error("First message")
        val first = builder.build()

        builder.warning("Second message")
        val second = builder.build()

        // First diagnostic should be unchanged
        assertEquals("First message", first.message)
        assertEquals(DiagnosticSeverity.Error, first.severity)

        // Second diagnostic should have new values
        assertEquals("Second message", second.message)
        assertEquals(DiagnosticSeverity.Warning, second.severity)
    }
}
