package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.api.ParserDiagnostic
import com.github.albertocavalcante.groovyparser.api.ParserRange
import com.github.albertocavalcante.groovyparser.api.ParserSeverity
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import io.mockk.mockk
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.DiagnosticSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompilationResultMapperTest {
    private val mapper = CompilationResultMapper()

    @Test
    fun `should map successful parse result`() {
        val content = "println 'hello'"
        val ast = mockk<ModuleNode>()
        val astModel = mockk<GroovyAstModel>()
        val compilationUnit = mockk<CompilationUnit>()
        val sourceUnit = mockk<SourceUnit>()
        val symbolTable = mockk<SymbolTable>()

        val parseResult = ParseResult(
            ast = ast,
            compilationUnit = compilationUnit,
            sourceUnit = sourceUnit,
            diagnostics = emptyList(),
            symbolTable = symbolTable,
            astModel = astModel,
        )

        val result = mapper.map(parseResult, content)

        assertTrue(result.isSuccess)
        assertEquals(ast, result.ast)
        assertEquals(content, result.sourceText)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `should map parse result with errors`() {
        val content = "invalid code"
        val diag = ParserDiagnostic(
            message = "Syntax error",
            severity = ParserSeverity.ERROR,
            range = ParserRange.singleLine(0, 0, 7),
            source = "parser",
        )
        val compilationUnit = mockk<CompilationUnit>()
        val sourceUnit = mockk<SourceUnit>()
        val symbolTable = mockk<SymbolTable>()
        val astModel = mockk<GroovyAstModel>()

        val parseResult = ParseResult(
            ast = null,
            compilationUnit = compilationUnit,
            sourceUnit = sourceUnit,
            diagnostics = listOf(diag),
            symbolTable = symbolTable,
            astModel = astModel,
        )

        val result = mapper.map(parseResult, content)

        assertFalse(result.isSuccess)
        assertNull(result.ast)
        assertEquals(1, result.diagnostics.size)
        assertEquals(DiagnosticSeverity.Error, result.diagnostics[0].severity)
        assertEquals("Syntax error", result.diagnostics[0].message)
    }

    @Test
    fun `should map from cache correctly`() {
        val content = "println 'hello'"
        val ast = mockk<ModuleNode>()
        val compilationUnit = mockk<CompilationUnit>()
        val sourceUnit = mockk<SourceUnit>()
        val symbolTable = mockk<SymbolTable>()
        val astModel = mockk<GroovyAstModel>()

        val parseResult = ParseResult(
            ast = ast,
            compilationUnit = compilationUnit,
            sourceUnit = sourceUnit,
            diagnostics = emptyList(),
            symbolTable = symbolTable,
            astModel = astModel,
        )

        val result = mapper.mapFromCache(parseResult, content)

        assertNotNull(result)
        assertTrue(result!!.isSuccess)
        assertEquals(ast, result.ast)
    }

    @Test
    fun `should return null when mapping from cache without AST`() {
        val compilationUnit = mockk<CompilationUnit>()
        val sourceUnit = mockk<SourceUnit>()
        val symbolTable = mockk<SymbolTable>()
        val astModel = mockk<GroovyAstModel>()

        val parseResult = ParseResult(
            ast = null,
            compilationUnit = compilationUnit,
            sourceUnit = sourceUnit,
            diagnostics = emptyList(),
            symbolTable = symbolTable,
            astModel = astModel,
        )

        val result = mapper.mapFromCache(parseResult, "")

        assertNull(result)
    }
}
