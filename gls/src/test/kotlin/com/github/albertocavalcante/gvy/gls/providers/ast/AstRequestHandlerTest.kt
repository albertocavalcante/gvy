// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AstRequestHandlerTest {

    private val compilationService = mockk<GroovyCompilationService>()
    private val coreConverter = mockk<CoreAstConverter>()
    private val nativeConverter = mockk<NativeAstConverter>()
    private val documents = DocumentProvider()

    @Test
    fun `getAst returns Core AST when requested`(): Unit = runTest {
        val uri = "file:///test.groovy"
        val params = AstParams(uri, "core")

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

        documents.put(URI.create(uri), "", 1)
        coEvery { compilationService.compileTransient(URI.create(uri), "", Phases.CANONICALIZATION) } returns
            parseResult
        val handler = AstRequestHandler(
            compilationService,
            coreConverter,
            nativeConverter,
            documents,
            supervisedScope(),
        )

        // Mock converter
        val astDto = CoreAstNodeDto("node-1", "CompilationUnit", null, emptyList(), emptyMap())
        // We are testing that the handler calls the converter.
        // Note: GroovyParser.convertFromNative is a static call that runs on the real implementation.
        // We mock the injected converter to return our expected DTO.
        coEvery { coreConverter.convert(any(), "") } returns astDto

        val result = handler.getAst(params).await()

        assertNotNull(result)
        assertEquals("core", result.parser)
        val expectedJson = "{\"dtoKind\":\"core\", \"id\":\"node-1\", \"type\":\"CompilationUnit\", " +
            "\"range\":null, \"children\":[], \"properties\":{}}"
        val expectedElement = Json.parseToJsonElement(expectedJson)
        val actualElement = Json.parseToJsonElement(result.ast)
        assertEquals(expectedElement, actualElement)
    }

    @Test
    fun `AST export omits ranges ambiguous after a supplementary character`(): Unit = runTest {
        val uri = URI("file:///test.groovy")
        val source = "class Example { String before; String emoji = '😀'; String after }"
        val parsed = GroovyParserFacade().parse(ParseRequest(uri = uri, content = source))
        assertNotNull(parsed.ast)
        documents.put(uri, source, 1)
        coEvery { compilationService.compileTransient(uri, source, Phases.CANONICALIZATION) } returns parsed
        val realHandler =
            AstRequestHandler(
                compilationService,
                CoreAstConverter(),
                NativeAstConverter(),
                documents,
                supervisedScope(),
            )

        listOf("native", "core").forEach { parser ->
            val result = realHandler.getAst(AstParams(uri.toString(), parser)).await()
            val dto = Json.decodeFromString<AstNodeDto>(result.ast)
            val cls = dto.children.single { it.properties["name"] == "Example" }
            val after = cls.children.single {
                it.properties["name"] == "after" && it.type in setOf("FieldNode", "FieldDeclaration")
            }
            assertEquals(null, after.range, parser)
        }
    }

    @Test
    fun `AST uses synchronized Unicode CRLF text and reports its identity`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh { String face = '😀' }\r\n"
        val parsed = GroovyParserFacade().parse(ParseRequest(uri = uri, content = source))
        documents.put(uri, source, 7)
        coEvery { compilationService.compileTransient(uri, source, Phases.CANONICALIZATION) } returns parsed
        val handler = AstRequestHandler(
            compilationService,
            CoreAstConverter(),
            NativeAstConverter(),
            documents,
            supervisedScope(),
        )
        val hash = "ffde05cbe9db6557f55bd0d9388b9d60650c6ec0db82ce7c4fea0d7515ae943e"

        for (parser in listOf("native", "core")) {
            val result = handler.getAst(AstParams(uri.toString(), parser, hash)).await()
            assertEquals(7, result.documentVersion)
            assertEquals(hash, result.sourceHash)
            val tree = Json.decodeFromString<AstNodeDto>(result.ast)
            assertEquals("Fresh", tree.children.single { it.properties["name"] == "Fresh" }.properties["name"])
        }
    }

    @Test
    fun `AST rejects a reopened document even when text and version repeat`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh {}"
        val parsed = GroovyParserFacade().parse(ParseRequest(uri = uri, content = source))
        documents.put(uri, source, 1)
        coEvery { compilationService.compileTransient(uri, source, Phases.CANONICALIZATION) } coAnswers {
            documents.remove(uri)
            documents.put(uri, source, 1)
            parsed
        }
        val handler = AstRequestHandler(
            compilationService,
            CoreAstConverter(),
            NativeAstConverter(),
            documents,
            supervisedScope(),
        )
        val error = assertFailsWith<ResponseErrorException> {
            handler.getAst(AstParams(uri.toString(), "native")).await()
        }
        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    @Test
    fun `AST rejects mismatched hashes and invalid inputs`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        documents.put(uri, "class Fresh {}", 1)
        val handler = AstRequestHandler(
            compilationService,
            coreConverter,
            nativeConverter,
            documents,
            supervisedScope(),
        )
        val cases = listOf(
            AstParams(uri.toString(), "native", "wrong") to ResponseErrorCode.ContentModified,
            AstParams(uri.toString(), "unknown") to ResponseErrorCode.InvalidParams,
            AstParams("not a uri", "native") to ResponseErrorCode.InvalidParams,
            AstParams("file:relative.groovy", "native") to ResponseErrorCode.InvalidParams,
            AstParams(null, "native") to ResponseErrorCode.InvalidParams,
            AstParams(uri.toString(), null) to ResponseErrorCode.InvalidParams,
            AstParams("file:///missing.groovy", "native") to ResponseErrorCode.RequestFailed,
        )
        for ((params, expected) in cases) {
            val error = assertFailsWith<ResponseErrorException> { handler.getAst(params).await() }
            assertEquals(expected.value, error.responseError.code)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling AST request cancels transient compilation`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh {}"
        val started = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<ParseResult>()
        val cancelled = CompletableDeferred<Unit>()
        documents.put(uri, source, 1)
        coEvery { compilationService.compileTransient(uri, source, Phases.CANONICALIZATION) } coAnswers {
            started.complete(Unit)
            try {
                blocked.await()
            } catch (error: CancellationException) {
                cancelled.complete(Unit)
                throw error
            }
        }
        val handler = AstRequestHandler(
            compilationService,
            coreConverter,
            nativeConverter,
            documents,
            supervisedScope(),
        )
        val request = handler.getAst(AstParams(uri.toString(), "native"))
        runCurrent()
        assertTrue(started.isCompleted)
        request.cancel(true)
        runCurrent()
        assertTrue(request.isCancelled)
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `AST rejects an edit during compilation`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh {}"
        val parsed = GroovyParserFacade().parse(ParseRequest(uri = uri, content = source))
        documents.put(uri, source, 1)
        coEvery { compilationService.compileTransient(uri, source, Phases.CANONICALIZATION) } coAnswers {
            documents.update(uri, "class Newer {}", 2)
            parsed
        }
        val handler = AstRequestHandler(
            compilationService,
            CoreAstConverter(),
            NativeAstConverter(),
            documents,
            supervisedScope(),
        )
        val error = assertFailsWith<ResponseErrorException> {
            handler.getAst(AstParams(uri.toString(), "core")).await()
        }
        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    @Test
    fun `AST rejects a change made while the tree is converted`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh {}"
        val parsed = GroovyParserFacade().parse(ParseRequest(uri = uri, content = source))
        documents.put(uri, source, 1)
        coEvery { compilationService.compileTransient(uri, source, Phases.CANONICALIZATION) } returns parsed
        val dto = CoreAstNodeDto("node", "Module", null, emptyList(), emptyMap())
        every { nativeConverter.convert(any(), source) } answers {
            documents.update(uri, "class Newer {}", 2)
            dto
        }
        val handler = AstRequestHandler(
            compilationService,
            coreConverter,
            nativeConverter,
            documents,
            supervisedScope(),
        )
        val error = assertFailsWith<ResponseErrorException> {
            handler.getAst(AstParams(uri.toString(), "native")).await()
        }
        assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
    }

    @Test
    fun `AST reports unavailable parse tree as request failure`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh {}"
        val parsed = GroovyParserFacade().parse(ParseRequest(uri = uri, content = source)).copy(ast = null)
        documents.put(uri, source, 1)
        coEvery { compilationService.compileTransient(uri, source, Phases.CANONICALIZATION) } returns parsed
        val handler = AstRequestHandler(
            compilationService,
            coreConverter,
            nativeConverter,
            documents,
            supervisedScope(),
        )
        val error = assertFailsWith<ResponseErrorException> {
            handler.getAst(AstParams(uri.toString(), "native")).await()
        }
        assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code)
    }
    private fun TestScope.supervisedScope(): CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())
}
