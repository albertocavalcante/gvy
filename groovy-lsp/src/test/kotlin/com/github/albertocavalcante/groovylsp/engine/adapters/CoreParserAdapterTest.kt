package com.github.albertocavalcante.groovylsp.engine.adapters

import com.github.albertocavalcante.groovyparser.ParseResult
import com.github.albertocavalcante.groovyparser.Problem
import com.github.albertocavalcante.groovyparser.ProblemSeverity
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.github.albertocavalcante.groovyparser.Position as CorePosition
import com.github.albertocavalcante.groovyparser.Range as CoreRange

/**
 * Unit tests for [CoreParserAdapter].
 *
 * Note: Some tests are simplified because groovyparser-core's Node.range
 * has internal visibility for the setter.
 */
class CoreParserAdapterTest {

    @Test
    fun `isSuccessful returns true when no errors`() {
        val cu = CompilationUnit()
        val parseResult = ParseResult(cu, emptyList())
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        assertTrue(adapter.isSuccessful)
    }

    @Test
    fun `isSuccessful returns false when has errors`() {
        val cu = CompilationUnit()
        val problem = Problem("Syntax error", CorePosition(1, 1), ProblemSeverity.ERROR)
        val parseResult = ParseResult(cu, listOf(problem))
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        assertEquals(false, adapter.isSuccessful)
    }

    @Test
    fun `diagnostics are converted with correct severity`() {
        val cu = CompilationUnit()
        val errorProblem = Problem("Error message", CorePosition(1, 1), ProblemSeverity.ERROR)
        val warningProblem = Problem("Warning message", CorePosition(2, 1), ProblemSeverity.WARNING)
        val parseResult = ParseResult(cu, listOf(errorProblem, warningProblem))
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        assertEquals(2, adapter.diagnostics.size)
        assertEquals("Error message", adapter.diagnostics[0].message)
        assertEquals(DiagnosticSeverity.Error, adapter.diagnostics[0].severity)
        assertEquals("Warning message", adapter.diagnostics[1].message)
        assertEquals(DiagnosticSeverity.Warning, adapter.diagnostics[1].severity)
    }

    @Test
    fun `diagnostics have 0-based positions converted from 1-based`() {
        val cu = CompilationUnit()
        val range = CoreRange(CorePosition(5, 10), CorePosition(5, 20))
        val problem = Problem("Test", range, ProblemSeverity.ERROR)
        val parseResult = ParseResult(cu, listOf(problem))
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val diagnostic = adapter.diagnostics[0]
        // 1-based (5, 10) should become 0-based (4, 9)
        assertEquals(4, diagnostic.range.start.line)
        assertEquals(9, diagnostic.range.start.character)
        assertEquals(4, diagnostic.range.end.line)
        assertEquals(20, diagnostic.range.end.character)
    }

    @Test
    fun `diagnostics handle position-only problems`() {
        val cu = CompilationUnit()
        val problem = Problem("Point error", CorePosition(3, 5), ProblemSeverity.ERROR)
        val parseResult = ParseResult(cu, listOf(problem))
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val diagnostic = adapter.diagnostics[0]
        // Position (3, 5) -> 0-based (2, 4) as both start and end
        assertEquals(2, diagnostic.range.start.line)
        assertEquals(4, diagnostic.range.start.character)
    }

    @Test
    fun `diagnostics handle problems without location`() {
        val cu = CompilationUnit()
        val problem = Problem(message = "General error", severity = ProblemSeverity.ERROR)
        val parseResult = ParseResult(cu, listOf(problem))
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val diagnostic = adapter.diagnostics[0]
        // Default to (0, 0) when no location
        assertEquals(0, diagnostic.range.start.line)
        assertEquals(0, diagnostic.range.start.character)
    }

    @Test
    fun `allSymbols returns class with correct name`() {
        val cu = CompilationUnit()
        val classDecl = ClassDeclaration("MyClass")
        cu.addType(classDecl)

        val parseResult = ParseResult(cu, emptyList())
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(1, symbols.size)
        assertEquals("MyClass", symbols[0].name)
        assertEquals(UnifiedNodeKind.CLASS, symbols[0].kind)
    }

    @Test
    fun `allSymbols identifies interfaces correctly`() {
        val cu = CompilationUnit()
        val interfaceDecl = ClassDeclaration("MyInterface", isInterface = true)
        cu.addType(interfaceDecl)

        val parseResult = ParseResult(cu, emptyList())
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(1, symbols.size)
        assertEquals("MyInterface", symbols[0].name)
        assertEquals(UnifiedNodeKind.INTERFACE, symbols[0].kind)
    }

    @Test
    fun `allSymbols identifies enums correctly`() {
        val cu = CompilationUnit()
        val enumDecl = ClassDeclaration("MyEnum", isEnum = true)
        cu.addType(enumDecl)

        val parseResult = ParseResult(cu, emptyList())
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(1, symbols.size)
        assertEquals("MyEnum", symbols[0].name)
        assertEquals(UnifiedNodeKind.ENUM, symbols[0].kind)
    }

    @Test
    fun `nodeAt returns null when no parse result`() {
        val parseResult = ParseResult<CompilationUnit>(null, emptyList())
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val node = adapter.nodeAt(Position(1, 0))

        assertNull(node)
    }

    @Test
    fun `allSymbols returns empty when no types`() {
        val cu = CompilationUnit()
        val parseResult = ParseResult(cu, emptyList())
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(0, symbols.size)
    }

    @Test
    fun `allSymbols returns empty when parse result is null`() {
        val parseResult = ParseResult<CompilationUnit>(null, emptyList())
        val adapter = CoreParserAdapter(parseResult, "file:///Test.groovy")

        val symbols = adapter.allSymbols()

        assertEquals(0, symbols.size)
    }
}
