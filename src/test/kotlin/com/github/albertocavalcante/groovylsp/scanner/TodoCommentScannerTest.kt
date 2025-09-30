package com.github.albertocavalcante.groovylsp.scanner

import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoCommentScannerTest {

    private val scanner = TodoCommentScanner()

    @Test
    fun `should detect single-line TODO comments`() {
        val sourceCode = """
            class TestClass {
                // TODO: implement this method
                def method() {}

                // FIXME: fix the bug here
                def anotherMethod() {}
            }
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(2, diagnostics.size)

        val todoDiagnostic = diagnostics[0]
        assertEquals("TODO: implement this method", todoDiagnostic.message)
        assertEquals(DiagnosticSeverity.Information, todoDiagnostic.severity)
        assertEquals("todo-scanner", todoDiagnostic.source)
        assertEquals(Either.forLeft<String, Int>("todo"), todoDiagnostic.code)

        val fixmeDiagnostic = diagnostics[1]
        assertEquals("FIXME: fix the bug here", fixmeDiagnostic.message)
        assertEquals(DiagnosticSeverity.Warning, fixmeDiagnostic.severity)
        assertEquals(Either.forLeft<String, Int>("fixme"), fixmeDiagnostic.code)
    }

    @Test
    fun `should detect TODO without colon`() {
        val sourceCode = """
            // TODO implement this
            def method() {}
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(1, diagnostics.size)
        assertEquals("TODO: implement this", diagnostics[0].message)
    }

    @Test
    fun `should detect case-insensitive TODO patterns`() {
        val sourceCode = """
            // todo: lowercase todo
            // Todo: mixed case todo
            // TODO: uppercase todo
            // fixme: lowercase fixme
            // FIXME: uppercase fixme
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(5, diagnostics.size)
        assertTrue(diagnostics.all { it.message.startsWith("TODO:") || it.message.startsWith("FIXME:") })
    }

    @Test
    fun `should detect multi-line TODO comments`() {
        val sourceCode = """
            /*
             * TODO: this is a multi-line
             * TODO comment that needs attention
             */
            class TestClass {}
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(2, diagnostics.size)
        assertEquals("TODO: this is a multi-line", diagnostics[0].message)
        assertEquals("TODO: comment that needs attention", diagnostics[1].message)
    }

    @Test
    fun `should detect various comment patterns`() {
        val sourceCode = """
            // TODO: basic todo
            // FIXME: basic fixme
            // XXX: dangerous code
            // HACK: temporary workaround
            // NOTE: important note
            // BUG: known bug
            // OPTIMIZE: performance issue
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(7, diagnostics.size)

        val severityMap = diagnostics.associate {
            it.code.toString().uppercase() to it.severity
        }

        assertEquals(DiagnosticSeverity.Information, severityMap["TODO"])
        assertEquals(DiagnosticSeverity.Warning, severityMap["FIXME"])
        assertEquals(DiagnosticSeverity.Warning, severityMap["XXX"])
        assertEquals(DiagnosticSeverity.Hint, severityMap["HACK"])
        assertEquals(DiagnosticSeverity.Information, severityMap["NOTE"])
        assertEquals(DiagnosticSeverity.Error, severityMap["BUG"])
        assertEquals(DiagnosticSeverity.Hint, severityMap["OPTIMIZE"])
    }

    @Test
    fun `should handle empty content`() {
        val diagnostics = scanner.scanForTodos("", "test.groovy")
        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should handle content without comments`() {
        val sourceCode = """
            class TestClass {
                def method() {
                    return "no comments here"
                }
            }
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")
        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `should not detect TODO in string literals`() {
        val sourceCode = """
            def message = "TODO: this is not a real todo"
            // TODO: this is a real todo
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(1, diagnostics.size)
        assertEquals("TODO: this is a real todo", diagnostics[0].message)
    }

    @Test
    fun `should detect inline multi-line comment TODOs`() {
        val sourceCode = """
            def method() { /* TODO: inline todo */ }
            def another() { /* FIXME: inline fixme */ }
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(2, diagnostics.size)
        assertEquals("TODO: inline todo", diagnostics[0].message)
        assertEquals("FIXME: inline fixme", diagnostics[1].message)
    }

    @Test
    fun `should provide correct line and column positions`() {
        val sourceCode = """
            class TestClass {
                // TODO: line 2 comment
                def method() {}
            }
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(1, diagnostics.size)
        val diagnostic = diagnostics[0]

        // Should point to line 1 (0-indexed), starting at the comment
        assertEquals(1, diagnostic.range.start.line)
        assertTrue(diagnostic.range.start.character >= 0)
        assertEquals(1, diagnostic.range.end.line)
        assertTrue(diagnostic.range.end.character > diagnostic.range.start.character)
    }

    @Test
    fun `should check pattern recognition methods`() {
        assertTrue(scanner.isRecognizedPattern("TODO"))
        assertTrue(scanner.isRecognizedPattern("todo"))
        assertTrue(scanner.isRecognizedPattern("FIXME"))
        assertTrue(scanner.isRecognizedPattern("fixme"))
        assertTrue(scanner.isRecognizedPattern("XXX"))
        assertFalse(scanner.isRecognizedPattern("NOTRECOGNIZED"))

        val patterns = scanner.getSupportedPatterns()
        assertTrue(patterns.containsKey("TODO"))
        assertTrue(patterns.containsKey("FIXME"))
        assertTrue(patterns.containsKey("XXX"))
        assertEquals(DiagnosticSeverity.Information, patterns["TODO"])
        assertEquals(DiagnosticSeverity.Warning, patterns["FIXME"])
    }

    @Test
    fun `should handle custom patterns`() {
        val customPatterns = mapOf(
            "CUSTOM" to DiagnosticSeverity.Error,
            "REVIEW" to DiagnosticSeverity.Information,
        )
        val customScanner = TodoCommentScanner(customPatterns)

        val sourceCode = """
            // CUSTOM: custom pattern
            // REVIEW: review this code
            // TODO: this should not be detected
        """.trimIndent()

        val diagnostics = customScanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(2, diagnostics.size)
        assertEquals("CUSTOM: custom pattern", diagnostics[0].message)
        assertEquals(DiagnosticSeverity.Error, diagnostics[0].severity)
        assertEquals("REVIEW: review this code", diagnostics[1].message)
        assertEquals(DiagnosticSeverity.Information, diagnostics[1].severity)
    }

    @Test
    fun `should handle comments with special characters`() {
        val sourceCode = """
            // TODO: fix bug #123 - urgent!
            // FIXME: handle edge case (when x > 100%)
            // NOTE: see issue https://github.com/example/repo/issues/456
        """.trimIndent()

        val diagnostics = scanner.scanForTodos(sourceCode, "test.groovy")

        assertEquals(3, diagnostics.size)
        assertEquals("TODO: fix bug #123 - urgent!", diagnostics[0].message)
        assertEquals("FIXME: handle edge case (when x > 100%)", diagnostics[1].message)
        assertEquals("NOTE: see issue https://github.com/example/repo/issues/456", diagnostics[2].message)
    }
}
