package com.github.albertocavalcante.gvy.gls.providers.folding

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.compilation.CompiledDocumentSnapshot
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldingRangeCompletenessTest {
    private val uri = URI.create("file:///FoldingComplete.groovy")
    private val documents = DocumentProvider()
    private val actual = GroovyCompilationService()
    private val compilation = spyk(actual)
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val service = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents),
    )
    private val classSource = "class Sample {\n void run() {\n  println 1\n }\n}\n"

    @AfterEach
    fun close() {
        scope.cancel()
        actual.clearCaches()
    }

    @Test
    fun `imports and block comments have their own folding kinds`(): Unit = runBlocking {
        val source = "import java.util.List\nimport java.util.Map\n\n/**\n * Docs\n */\nclass Sample {}\n"
        documents.put(uri, source, 1)
        actual.compile(uri, source)
        val ranges = request()
        assertTrue(ranges.any { it.kind == FoldingRangeKind.Imports && it.startLine == 0 && it.endLine == 1 })
        assertTrue(ranges.any { it.kind == FoldingRangeKind.Comment && it.startLine == 3 && it.endLine == 5 })
    }

    @Test
    fun `a cold folding query returns structure without publishing shared state`() {
        documents.put(uri, classSource, 1)
        assertTrue(request().any { it.startLine == 0 && it.endLine == 3 })
        assertNull(compilation.getCompiledSnapshot(uri))
        assertTrue(compilation.getWorkspaceSymbolIndex()?.implementationSnapshot?.containsKey(uri) != true)
    }

    @Test
    fun `folds from older cached text cannot escape into the current document`(): Unit = runBlocking {
        actual.compile(uri, classSource)
        documents.put(uri, "def fresh = 1\n", 2)
        assertTrue(request().isEmpty())
    }

    @Test
    fun `class and method folds project protocol lines across all line endings`(): Unit = runBlocking {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val source = listOf("// header", "class Sample {", " void run() {", "  println 1", " }", "}")
                .joinToString(ending)
            documents.put(uri, source, 1)
            actual.compile(uri, source)
            val ranges = request()
            assertTrue(ranges.any { it.startLine == 1 && it.endLine == 4 }, ending)
            assertTrue(ranges.any { it.startLine == 2 && it.endLine == 3 }, ending)
        }
    }

    @Test
    fun `foreign AST classes never contribute folds for the requested URI`(): Unit = runBlocking {
        val source = "\n\n\n\n\n"
        documents.put(uri, source, 1)
        actual.compile(uri, source)
        val cached = assertNotNull(compilation.getCompiledSnapshot(uri))
        val foreign = ClassNode("Foreign", 0, ClassHelper.OBJECT_TYPE).apply {
            lineNumber = 1
            columnNumber = 1
            lastLineNumber = 5
            lastColumnNumber = 1
        }
        val model = mockk<GroovyAstModel>(relaxed = true)
        every { model.getAllClassNodes() } returns listOf(foreign)
        every { model.getNodes(uri) } returns listOf(foreign)
        every { model.getUri(foreign) } returns URI.create("file:///Other.groovy")
        every { compilation.getAstModel(uri) } returns model
        every { compilation.getCompiledSnapshot(uri) } returns CompiledDocumentSnapshot(
            source,
            cached.parseResult.copy(astModel = model),
        )
        assertTrue(request().isEmpty())
    }

    @Test
    fun `unknown documents have no folds or query publication`() {
        assertTrue(request().isEmpty())
        assertNull(compilation.getCompiledSnapshot(uri))
    }

    @Test
    fun `a document version change during transient parsing rejects without publishing`(): Unit = runBlocking {
        documents.put(uri, classSource, 1)
        val parsed = actual.compileTransient(uri, classSource, Phases.CONVERSION)
        coEvery { compilation.compileTransient(uri, classSource, any()) } coAnswers {
            documents.put(uri, classSource, 2)
            parsed
        }
        val error = assertThrows<ExecutionException> { request() }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
        assertNull(compilation.getCompiledSnapshot(uri))
    }

    @Test
    fun `a change after matching-cache acquisition rejects before using its AST`(): Unit = runBlocking {
        documents.put(uri, classSource, 1)
        actual.compile(uri, classSource)
        val cached = assertNotNull(compilation.getCompiledSnapshot(uri))
        every { compilation.getCompiledSnapshot(uri) } answers {
            documents.put(uri, classSource, 2)
            cached
        }
        val error = assertThrows<ExecutionException> { request() }
        assertEquals(
            ResponseErrorCode.ContentModified.value,
            assertIs<ResponseErrorException>(error.cause).responseError.code,
        )
    }

    @Test
    fun `transient cancellation propagates to the folding future`() {
        documents.put(uri, classSource, 1)
        coEvery { compilation.compileTransient(uri, classSource, any()) } throws CancellationException("cancelled")
        assertThrows<CancellationException> { request() }
    }

    @Test
    fun `comment and import text inside strings does not supply lexical folds`() {
        val source = "def text = \"\"\"\n/*\n import imaginary.Type\n */\n\"\"\"\n"
        documents.put(uri, source, 1)
        val ranges = request()
        assertTrue(ranges.none { it.kind == FoldingRangeKind.Comment || it.kind == FoldingRangeKind.Imports })
    }

    @Test
    fun `block comments have standalone comment folds`() {
        documents.put(uri, "/**\n * Docs\n */\nclass Sample {}\n", 1)
        assertTrue(request().any { it.kind == FoldingRangeKind.Comment && it.startLine == 0 && it.endLine == 2 })
    }

    @Test
    fun `import groups and comment folds retain physical lines after supplementary text`() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val source = listOf(
                "/* 😀 */",
                "import java.util.List",
                "/**",
                " * docs",
                " */",
                "import java.util.Map",
                "class Sample {}",
            ).joinToString(ending)
            documents.put(uri, source, 1)
            val ranges = request()
            assertTrue(
                ranges.any {
                    it.kind == FoldingRangeKind.Imports && it.startLine == 1 && it.endLine == 5
                },
                ending,
            )
            assertTrue(
                ranges.any {
                    it.kind == FoldingRangeKind.Comment && it.startLine == 2 && it.endLine == 4
                },
                ending,
            )
        }
    }

    @Test
    fun `proven block comments remain foldable around incomplete code`() {
        val source = "/**\n * docs\n */\nclass Broken {\n def method( {\n"
        documents.put(uri, source, 1)
        assertTrue(request().any { it.kind == FoldingRangeKind.Comment && it.startLine == 0 && it.endLine == 2 })
        assertNull(compilation.getCompiledSnapshot(uri))
    }

    @Test
    fun `an unterminated comment has no speculative comment fold`() {
        documents.put(uri, "/**\n * no terminator\n", 1)
        assertTrue(request().none { it.kind == FoldingRangeKind.Comment })
    }

    @Test
    fun `multiline generic headers do not create class declaration folds`() {
        documents.put(uri, "class Sample extends ArrayList<\n String\n> {\n}\n", 1)
        val regions = request().filter { it.kind == FoldingRangeKind.Region }
        assertEquals(listOf(0 to 2), regions.map { it.startLine to it.endLine })
    }

    @Test
    fun `same-name generic field references do not inherit declaration eligibility`() {
        documents.put(uri, "class Sample<T> {\n Sample<\n String\n > field\n}\n", 1)
        val regions = request().filter { it.kind == FoldingRangeKind.Region }
        assertEquals(listOf(0 to 3), regions.map { it.startLine to it.endLine })
    }

    private fun request(): List<FoldingRange> = service.foldingRange(
        FoldingRangeRequestParams().apply { textDocument = TextDocumentIdentifier(uri.toString()) },
    ).get(5, TimeUnit.SECONDS)
}
