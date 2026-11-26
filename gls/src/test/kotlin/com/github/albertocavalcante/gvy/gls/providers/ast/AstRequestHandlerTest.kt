// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.AnalysisCompilerPolicy
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.nativeapi.ParseOutcome
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import io.mockk.MockKMatcherScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AstRequestHandlerTest {

    private val coreConverter = mockk<CoreAstConverter>()
    private val nativeConverter = mockk<NativeAstConverter>()
    private val documents = DocumentProvider()
    private val worker = mockk<WorkerSession>()
    private val compilationService =
        GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { worker })

    @AfterEach
    fun closeCompiler() {
        compilationService.shutdown()
    }

    private fun MockKMatcherScope.requestFor(uri: URI, content: String): ParseRequest = match {
        it.uri == uri && it.content == content && it.compilePhase == Phases.CANONICALIZATION
    }

    @Test
    fun `getAst returns Core AST when requested`(): Unit = runTest {
        val uri = "file:///test.groovy"
        val params = AstParams(uri, "core")

        val source = "class Example {}"
        val parseResult = GroovyParserFacade().parse(
            ParseRequest(URI.create(uri), source, compilePhase = Phases.CANONICALIZATION),
        )
        try {
            assertEquals(Phases.CANONICALIZATION, parseResult.sourceUnit.phase)
            assertTrue(parseResult.sourceUnit.isPhaseComplete)
            assertNotNull(parseResult.ast)

            documents.put(URI.create(uri), source, DocumentVersion.of(1))
            every { worker.parse(requestFor(URI.create(uri), source)) } returns
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
            coEvery { coreConverter.convert(any(), source) } returns astDto

            val result = handler.getAst(params).await()

            assertNotNull(result)
            assertEquals("core", result.parser)
            val expectedJson = "{\"dtoKind\":\"core\", \"id\":\"node-1\", \"type\":\"CompilationUnit\", " +
                "\"range\":null, \"children\":[], \"properties\":{}}"
            val expectedElement = Json.parseToJsonElement(expectedJson)
            val actualElement = Json.parseToJsonElement(result.ast)
            assertEquals(expectedElement, actualElement)
        } finally {
            parseResult.compilationUnit.classLoader.close()
        }
    }

    @Test
    fun `AST export omits ranges ambiguous after a supplementary character`(): Unit = runTest {
        val uri = URI("file:///test.groovy")
        val source = "class Example { String before; String emoji = '😀'; String after }"
        val parsed = GroovyParserFacade().parse(
            ParseRequest(uri = uri, content = source, compilePhase = Phases.CANONICALIZATION),
        )
        assertNotNull(parsed.ast)
        documents.put(uri, source, DocumentVersion.of(1))
        every { worker.parse(requestFor(uri, source)) } returns parsed
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
        val parsed = GroovyParserFacade().parse(
            ParseRequest(uri = uri, content = source, compilePhase = Phases.CANONICALIZATION),
        )
        documents.put(uri, source, DocumentVersion.of(7))
        every { worker.parse(requestFor(uri, source)) } returns parsed
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
        val parsed = GroovyParserFacade().parse(
            ParseRequest(uri = uri, content = source, compilePhase = Phases.CANONICALIZATION),
        )
        documents.put(uri, source, DocumentVersion.of(1))
        every { worker.parse(requestFor(uri, source)) } answers {
            documents.remove(uri)
            documents.put(uri, source, DocumentVersion.of(1))
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
        documents.put(uri, "class Fresh {}", DocumentVersion.of(1))
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
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        documents.put(uri, source, DocumentVersion.of(1))
        every { worker.parse(requestFor(uri, source)) } answers {
            val request = firstArg<ParseRequest>()
            started.countDown()
            check(release.await(30, TimeUnit.SECONDS))
            try {
                request.checkCancelled()
                error("The parser did not observe request cancellation")
            } catch (error: CancellationException) {
                cancelled.countDown()
                throw error
            }
        }
        val owner = SupervisorJob()
        val handler = AstRequestHandler(
            compilationService,
            coreConverter,
            nativeConverter,
            documents,
            CoroutineScope(owner + Dispatchers.IO),
        )
        try {
            val request = handler.getAst(AstParams(uri.toString(), "native"))
            assertTrue(started.await(30, TimeUnit.SECONDS))
            request.cancel(true)
            release.countDown()
            assertTrue(request.isCancelled)
            assertTrue(cancelled.await(30, TimeUnit.SECONDS))
        } finally {
            owner.cancel()
            release.countDown()
        }
    }

    @Test
    fun `AST rejects an edit during compilation`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh {}"
        val parsed = GroovyParserFacade().parse(
            ParseRequest(uri = uri, content = source, compilePhase = Phases.CANONICALIZATION),
        )
        documents.put(uri, source, DocumentVersion.of(1))
        every { worker.parse(requestFor(uri, source)) } answers {
            documents.update(uri, "class Newer {}", DocumentVersion.of(2))
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
        val parsed = GroovyParserFacade().parse(
            ParseRequest(uri = uri, content = source, compilePhase = Phases.CANONICALIZATION),
        )
        documents.put(uri, source, DocumentVersion.of(1))
        every { worker.parse(requestFor(uri, source)) } returns parsed
        val dto = CoreAstNodeDto("node", "Module", null, emptyList(), emptyMap())
        every { nativeConverter.convert(any(), source) } answers {
            documents.update(uri, "class Newer {}", DocumentVersion.of(2))
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
    fun `AST rejects an unavailable or mismatched requested source tree`(): Unit = runTest {
        val uri = URI("file:///snapshot.groovy")
        val source = "class Fresh {}"
        val parser = GroovyParserFacade()
        val parsed = parser.parse(ParseRequest(uri, source, compilePhase = Phases.CANONICALIZATION))
        val other = parser.parse(
            ParseRequest(URI("file:///Other.groovy"), "class Other {}", compilePhase = Phases.CANONICALIZATION),
        )
        documents.put(uri, source, DocumentVersion.of(1))
        val handler = AstRequestHandler(
            compilationService,
            coreConverter,
            nativeConverter,
            documents,
            supervisedScope(),
        )
        try {
            val unavailable = listOf(
                parsed.copy(ast = null),
                parsed.copy(ast = other.ast),
                parsed.copy(sourceUnit = other.sourceUnit),
                other,
            )
            for (result in unavailable) {
                every { worker.parse(requestFor(uri, source)) } returns result
                for (projection in listOf("native", "core")) {
                    val error = assertFailsWith<ResponseErrorException> {
                        handler.getAst(AstParams(uri.toString(), projection)).await()
                    }
                    assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code, projection)
                }
            }
        } finally {
            parsed.compilationUnit.classLoader.close()
            other.compilationUnit.classLoader.close()
        }
    }

    enum class LoaderCompletion {
        SUCCESS,
        UNAVAILABLE_AST,
        CONVERTER_FAILURE,
        POST_PARSE_CANCELLATION,
        STALE_SOURCE,
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest(name = "{0}")
    @EnumSource(LoaderCompletion::class)
    fun `AST releases only its owned transient loader for every completion path`(
        completion: LoaderCompletion,
        @TempDir directory: Path,
    ): Unit = runTest {
        val uri = URI("file:///LoaderFixture.groovy")
        val source = "class LoaderFixture {}"
        val requestOwner = SupervisorJob()
        OwnedParseFixture(directory, javaClass.classLoader).use { fixture ->
            val compiled = fixture.compile(uri, source)
            val parsed = if (completion == LoaderCompletion.UNAVAILABLE_AST) compiled.copy(ast = null) else compiled
            documents.put(uri, source, DocumentVersion.of(1))
            every { worker.parse(requestFor(uri, source)) } answers {
                // TODO(#2063): Investigate pinned jqwik/Platform discovery of coroutine enum dispatch.
                //   Recheck anonymous enum helpers when qualifying the supported dependency stack.
                //   See: https://github.com/albertocavalcante/gvy/issues/2063
                if (completion == LoaderCompletion.POST_PARSE_CANCELLATION) requestOwner.cancel()
                if (completion ==
                    LoaderCompletion.STALE_SOURCE
                ) {
                    documents.update(uri, "class Edited {}", DocumentVersion.of(2))
                }
                parsed
            }
            val conversionFailure = IllegalStateException("controlled projection failure")
            every { coreConverter.convert(any(), source) } throws conversionFailure
            val handler = AstRequestHandler(
                compilationService,
                coreConverter,
                NativeAstConverter(),
                documents,
                CoroutineScope(coroutineContext + requestOwner),
            )
            try {
                assertLoaderOutcome(completion, handler, uri, conversionFailure)
                runCurrent()
                fixture.assertReleased()
            } finally {
                requestOwner.cancel()
            }
        }
    }

    private suspend fun assertLoaderOutcome(
        completion: LoaderCompletion,
        handler: AstRequestHandler,
        uri: URI,
        conversionFailure: IllegalStateException,
    ) {
        val projection = if (completion == LoaderCompletion.CONVERTER_FAILURE) "core" else "native"
        when (completion) {
            LoaderCompletion.SUCCESS -> assertTrue(
                handler.getAst(AstParams(uri.toString(), projection)).await().ast.isNotBlank(),
            )
            LoaderCompletion.UNAVAILABLE_AST,
            LoaderCompletion.STALE_SOURCE,
            -> {
                val error = assertFailsWith<ResponseErrorException> {
                    handler.getAst(AstParams(uri.toString(), projection)).await()
                }
                val expected = if (completion == LoaderCompletion.STALE_SOURCE) {
                    ResponseErrorCode.ContentModified
                } else {
                    ResponseErrorCode.RequestFailed
                }
                assertEquals(expected.value, error.responseError.code)
            }
            LoaderCompletion.CONVERTER_FAILURE -> {
                val error = assertFailsWith<IllegalStateException> {
                    handler.getAst(AstParams(uri.toString(), projection)).await()
                }
                // Coroutine stacktrace recovery may copy the exception across the future boundary.
                assertEquals(conversionFailure.message, error.message)
            }
            LoaderCompletion.POST_PARSE_CANCELLATION -> assertFailsWith<CancellationException> {
                handler.getAst(AstParams(uri.toString(), projection)).await()
            }
        }
    }

    private class OwnedParseFixture(private val directory: Path, parent: ClassLoader) : AutoCloseable {
        private val marker = UUID.randomUUID().toString()
        private val ownedResource = "gvy-ast-owned-$marker.txt"
        private val parentResource = "gvy-ast-parent-$marker.txt"
        private val transformResource = "gvy-ast-transform-$marker.txt"
        private val parentLoader = CountingLoader(parent)
        private val transformLoader = CountingLoader(parent)
        private val ownedLoader = CountingLoader(parentLoader)

        fun compile(uri: URI, source: String): ParseResult {
            addResource(parentLoader, "parent", parentResource)
            addResource(transformLoader, "transform", transformResource)
            addResource(ownedLoader, "owned", ownedResource)
            val configuration = CompilerConfiguration()
            AnalysisCompilerPolicy.apply(configuration)
            val unit = CompilationUnit(configuration, null, ownedLoader, transformLoader)
            val sourceUnit = unit.addSource(uri.path, source)
            unit.compile(Phases.CANONICALIZATION)
            assertSame(ownedLoader, unit.classLoader)
            assertSame(transformLoader, unit.transformLoader)
            assertEquals(Phases.CANONICALIZATION, sourceUnit.phase)
            assertTrue(sourceUnit.isPhaseComplete)
            assertEquals(0, ownedLoader.closes)
            assertEquals(0, parentLoader.closes)
            assertEquals(0, transformLoader.closes)
            assertNotNull(ownedLoader.getResource(ownedResource))
            assertNotNull(parentLoader.getResource(parentResource))
            assertNotNull(transformLoader.getResource(transformResource))
            val ast = assertNotNull(sourceUnit.ast)
            assertSame(sourceUnit, ast.context)
            val model = RecursiveAstVisitor(NodeRelationshipTracker())
            model.visitModule(ast, uri)
            val symbols = SymbolTable()
            symbols.buildFromVisitor(model)
            return ParseResult(
                ast = ast,
                compilationUnit = unit,
                sourceUnit = sourceUnit,
                diagnostics = emptyList(),
                symbolTable = symbols,
                astModel = model,
                outcome = ParseOutcome(requestedPhase = Phases.CANONICALIZATION),
            )
        }

        fun assertReleased() {
            assertEquals(1, ownedLoader.closes, "The request-owned loader must close exactly once")
            assertNull(ownedLoader.getResource(ownedResource), "Its own resource must no longer be reachable")
            assertEquals(0, parentLoader.closes, "The parent is borrowed")
            assertNotNull(parentLoader.getResource(parentResource))
            assertEquals(0, transformLoader.closes, "The shared transform loader is borrowed")
            assertNotNull(transformLoader.getResource(transformResource))
        }

        private fun addResource(loader: GroovyClassLoader, child: String, name: String) {
            val root = Files.createDirectories(directory.resolve(child))
            Files.writeString(root.resolve(name), "test resource")
            loader.addClasspath(root.toString())
            assertNotNull(loader.getResource(name), "Resource control must be live before dispatch")
        }

        override fun close() {
            try {
                if (ownedLoader.closes == 0) ownedLoader.close()
            } finally {
                try {
                    transformLoader.close()
                } finally {
                    parentLoader.close()
                }
            }
        }
    }

    private class CountingLoader(parent: ClassLoader) : GroovyClassLoader(parent) {
        var closes = 0
            private set

        override fun close() {
            super.close()
            closes++
        }
    }

    private fun TestScope.supervisedScope(): CoroutineScope = CoroutineScope(coroutineContext + SupervisorJob())
}
