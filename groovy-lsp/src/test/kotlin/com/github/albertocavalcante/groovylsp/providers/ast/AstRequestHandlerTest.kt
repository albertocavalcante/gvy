package com.github.albertocavalcante.groovylsp.providers.ast

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovyparser.api.ParseResult
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AstRequestHandlerTest {

    private val compilationService = mockk<GroovyCompilationService>()
    private val coreConverter = mockk<CoreAstConverter>()
    private val nativeConverter = mockk<NativeAstConverter>()
    private val handler = AstRequestHandler(compilationService, coreConverter, nativeConverter)

    @Test
    fun `getAst returns Core AST when requested`() = runTest {
        val uri = "file:///test.groovy"
        val params = AstParams(uri, "core")
        val content = "class Test {}"

        // Mock components for ParseResult
        val nativeAst = mockk<ModuleNode>(relaxed = true)
        val compilationUnit = mockk<CompilationUnit>()
        val sourceUnit = mockk<SourceUnit>()
        val symbolTable = mockk<SymbolTable>()
        val astModel = mockk<GroovyAstModel>()

        val parseResult = ParseResult(
            ast = nativeAst,
            compilationUnit = compilationUnit,
            sourceUnit = sourceUnit,
            diagnostics = emptyList(),
            symbolTable = symbolTable,
            astModel = astModel,
        )

        coEvery { compilationService.getParseResult(URI.create(uri)) } returns parseResult

        // Mock converter
        val astDto = CoreAstNodeDto("node-1", "CompilationUnit", null, emptyList(), emptyMap())
        // We are testing that the handler calls the converter.
        // Note: GroovyParser.convertFromNative is a static call that runs on the real implementation.
        // We mock the injected converter to return our expected DTO.
        coEvery { coreConverter.convert(any()) } returns astDto

        val result = handler.getAst(params).get()

        assertNotNull(result)
        assertEquals("core", result.parser)
        val expectedJson =
            "{\"dtoKind\":\"core\",\"id\":\"node-1\",\"type\":\"CompilationUnit\",\"range\":null,\"children\":[],\"properties\":{}}"
        val expectedElement = Json.parseToJsonElement(expectedJson)
        val actualElement = Json.parseToJsonElement(result.ast)
        assertEquals(expectedElement, actualElement)
    }
}
