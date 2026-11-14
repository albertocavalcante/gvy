// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.compilation.ClassNameQuery
import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.sources.MissingSourceReason
import com.github.albertocavalcante.gvy.gls.sources.ResolvedExternalSymbol
import com.github.albertocavalcante.gvy.gls.sources.ResolvedSourceRequest
import com.github.albertocavalcante.gvy.gls.sources.SourceContextIdentity
import com.github.albertocavalcante.gvy.gls.sources.SourceDefinition
import com.github.albertocavalcante.gvy.gls.sources.SourceFailure
import com.github.albertocavalcante.gvy.gls.sources.SourceLookupContext
import com.github.albertocavalcante.gvy.gls.sources.SourceLookupPolicy
import com.github.albertocavalcante.gvy.gls.sources.SourceLookupResult
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.sources.SourceOrigin
import com.github.albertocavalcante.gvy.gls.sources.SourceSpan
import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ImportNode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/** Only a captured origin and a verified declaration produce an external definition. */
class DefinitionResolverFallbackTest {
    private val document = URI.create("file:///project/Main.groovy")
    private val position = Position(3, 12)
    private val symbol = ResolvedExternalSymbol(
        "sample.Library",
        SourceOrigin.Archive(Path.of("/selected/library.jar")),
    )
    private val context = SourceLookupContext(SourceContextIdentity("selected-context")) { true }
    private val request = ResolvedSourceRequest(symbol, context)
    private val location = URI.create("file:///managed/view/Library.java")
    private val definition = SourceDefinition(SourceSpan(4, 0, 20, 1), SourceSpan(4, 13, 4, 20))
    private val compilation = mockk<GroovyCompilationService>(relaxed = true)
    private val navigator = mockk<SourceNavigator>()

    @Test
    fun `external class uses captured source origin and exact declaration and selection ranges`() = runBlocking {
        coEvery { navigator.locate(symbol, context) } returns
            SourceLookupResult.Located(location, symbol.binaryName, definition)
        val result = assertIs<DefinitionResolver.DefinitionResult.Binary>(
            resolver().findDefinitionAt(document, position),
        )
        assertEquals(location, result.uri)
        assertEquals(4, result.range?.start?.line)
        assertEquals(13, result.range?.start?.character)
        assertEquals(20, result.declarationRange?.end?.line)
        coVerify(exactly = 1) { navigator.locate(symbol, context) }
    }

    @Test
    fun `import head goes through the same captured source lookup`() = runBlocking {
        val importedSymbol =
            ResolvedExternalSymbol(
                "java.lang.String",
                SourceOrigin.Jdk(Path.of(System.getProperty("java.home")), "java.base"),
            )
        val importedRequest = ResolvedSourceRequest(importedSymbol, context)
        coEvery {
            compilation.captureSourceRequest(
                document,
                importedSymbol.binaryName,
                null,
                any(),
                SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
            )
        } returns importedRequest
        coEvery { navigator.locate(importedSymbol, context) } returns
            SourceLookupResult.Located(location, importedSymbol.binaryName, definition)
        val import = ImportNode(ClassHelper.make(String::class.java), null).apply {
            lineNumber = 3
            columnNumber = 1
            lastLineNumber = 3
            lastColumnNumber = 30
        }
        assertIs<DefinitionResolver.DefinitionResult.Binary>(resolver(import).findDefinitionAt(document, position))
        coVerify(exactly = 1) { navigator.locate(importedSymbol, context) }
    }

    @Test
    fun `missing source and file-only results do not manufacture exact definitions`() = runBlocking {
        val resolver = resolver()
        coEvery { navigator.locate(symbol, context) } returns
            SourceLookupResult.Unavailable(MissingSourceReason.NO_ATTACHMENT)
        assertEquals(null, resolver.findDefinitionAt(document, position))
        coEvery { navigator.locate(symbol, context) } returns SourceLookupResult.FileOnly(
            location,
            symbol.binaryName,
            MissingSourceReason.DECLARATION_NOT_LOCATED,
        )
        assertEquals(null, resolver.findDefinitionAt(document, position))
    }

    @Test
    fun `retired source context fails request with ContentModified`() = runBlocking {
        coEvery { navigator.locate(symbol, context) } returns SourceLookupResult.Stale
        val resolver = resolver()
        val failure = assertFailsWith<ResponseErrorException> { resolver.findDefinitionAt(document, position) }
        assertEquals(ResponseErrorCode.ContentModified.value, failure.responseError.code)
    }

    @Test
    fun `source IO failure fails request rather than silently falling back`() = runBlocking {
        coEvery { navigator.locate(symbol, context) } returns SourceLookupResult.Failed(SourceFailure.IO_FAILURE)
        val resolver = resolver()
        val failure = assertFailsWith<ResponseErrorException> { resolver.findDefinitionAt(document, position) }
        assertEquals(ResponseErrorCode.RequestFailed.value, failure.responseError.code)
    }

    @Test
    fun `source cancellation remains the same cancellation`() = runBlocking {
        val cancelled = CancellationException("source request cancelled")
        coEvery { navigator.locate(symbol, context) } throws cancelled
        val resolver = resolver()
        assertSame(cancelled, assertFailsWith<CancellationException> { resolver.findDefinitionAt(document, position) })
    }

    @Test
    fun `missing held compilation cannot fall back to process-global classpath`() = runBlocking {
        val resolver = resolver()
        coEvery {
            compilation.captureSourceRequest(
                document,
                symbol.binaryName,
                null,
                any(),
                SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
            )
        } returns null
        assertEquals(null, resolver.findDefinitionAt(document, position))
        coVerify(exactly = 0) { navigator.locate(any(), any()) }
    }

    @Test
    fun `workspace class resolution remains available before dependency sources`() = runBlocking {
        val ast = mockk<GroovyAstModel>()
        val workspaceUri = URI.create("file:///project/Library.groovy")
        val declaration = classNode()
        val symbol = com.github.albertocavalcante.groovyparser.ast.symbols.Symbol.Class.from(declaration, workspaceUri)
        every { ast.getNodeAt(document, position) } returns classNode()
        every { ast.getAllClassNodes() } returns emptyList()
        every { compilation.getAst(document) } returns null
        every {
            compilation.symbolIndexingService.findClasses(
                ClassNameQuery.Exact("Library"),
            )
        } returns listOf(symbol)
        val module = mockk<org.codehaus.groovy.ast.ModuleNode>()
        every { module.getNodeMetaData<SourceAnnotationIndex>(SourceAnnotationIndex::class.java) } returns null
        coEvery { compilation.ensureCompiled(workspaceUri) } returns CompilationResult.success(module)
        every { compilation.getAst(workspaceUri) } returns module
        every { module.classes } returns listOf(declaration)
        val result = assertIs<DefinitionResolver.DefinitionResult.Source>(
            DefinitionResolver(ast, SymbolTable(), compilation, navigator).findDefinitionAt(document, position),
        )
        assertEquals(workspaceUri, result.uri)
        assertSame(declaration, result.node)
    }

    private fun resolver(node: ASTNode = classNode()): DefinitionResolver {
        val ast = mockk<GroovyAstModel>()
        every { ast.getNodeAt(document, position) } returns node
        every { ast.getAllClassNodes() } returns emptyList()
        every { compilation.getAllSymbolStorages() } returns emptyMap()
        every { compilation.getAst(document) } returns null
        coEvery {
            compilation.captureSourceRequest(
                document,
                symbol.binaryName,
                null,
                any(),
                SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
            )
        } returns request
        return DefinitionResolver(ast, SymbolTable(), compilation, navigator)
    }

    private fun classNode() = ClassNode(symbol.binaryName, 0, null).apply {
        lineNumber = 3
        columnNumber = 8
        lastLineNumber = 3
        lastColumnNumber = 22
    }
}
