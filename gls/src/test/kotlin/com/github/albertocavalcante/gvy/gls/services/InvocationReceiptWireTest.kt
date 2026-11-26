// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.setTestClasspath
import com.github.albertocavalcante.gvy.gls.config.DiagnosticConfig
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.providers.ast.AstParams
import com.github.albertocavalcante.gvy.gls.providers.ast.AstRequestHandler
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InvocationReceiptWireTest {
    @TempDir
    lateinit var root: Path

    @ParameterizedTest
    @CsvSource("ast,false", "ast,true", "cli,false", "cli,true", "symbols,false", "symbols,true")
    fun `missing receipts qualify only an isolated invocation`(route: String, physical: Boolean): Unit =
        runBlocking {
            val uri = root.resolve("Target.groovy").toUri()
            val jar = root.resolve("strategy.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { it.finish() }
            val expected = if (physical) listOf(jar) else emptyList()
            val calls = AtomicInteger()
            withInvocationProof({ request, parsed ->
                assertEquals(uri, request.uri)
                assertEquals(SOURCE, request.content)
                assertEquals(expected, request.classpath)
                assertTrue(request.sourceRoots.isEmpty() && request.workspaceSources.isEmpty())
                assertTrue(request.openSources.isEmpty())
                val receipt = assertNotNull(parsed.classpathInputs)
                assertEquals(expected, receipt.classpath)
                assertTrue(receipt.classpathStamps.all { it != null })
                val ast = assertNotNull(parsed.ast)
                assertSame(parsed.sourceUnit, ast.context)
                assertTrue(assertNotNull(CompiledDocumentInput.of(ast)).matchesText(SOURCE))
                assertTrue(ast.classes.single().methods.any { it.name == "proofMethod" })
                calls.incrementAndGet()
                parsed.copy(classpathInputs = null)
            }) {
                compiler.workspaceManager.setTestClasspath(root) { expected }
                val model = compiler.workspaceManager.current()
                assertTrue(model.classpath.isEmpty() && model.sourceRoots.isEmpty() && model.sources.isEmpty())
                open(uri, SOURCE)
                if (route == "symbols") compiler.compile(uri, SOURCE)
                if (physical) {
                    val error = assertFailsWith<ResponseErrorException> { answer(route, uri) }
                    assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code)
                } else {
                    assertTrue(answer(route, uri))
                }
                assertTrue(calls.get() > 0, "The actual selected worker must have returned the receiptless result")
            }
        }

    private suspend fun InvocationProofWire.answer(route: String, uri: URI): Boolean = when (route) {
        "ast" -> {
            val result = ast.getAst(AstParams(uri.toString(), "native")).await()
            val snapshot = assertNotNull(documents.getSnapshot(uri))
            val dto = Json.decodeFromString<AstNodeDto>(result.ast)
            val method = dto.children.single { it.properties["name"] == "Target" }
                .children.any { it.properties["name"] == "proofMethod" }
            method && result.sourceHash == snapshot.contentHash && result.documentVersion == snapshot.version?.value
        }
        "cli" -> cli.diagnose(uri, SOURCE).any {
            it.code?.left == "unused-import" && it.message == "Unused import: java.util.HashMap as NeverUsed"
        }
        "symbols" -> workspace.symbol(WorkspaceSymbolParams("proofMethod")).await().left.single().let {
            it.name == "proofMethod" && it.location.uri == uri.toString()
        }
        else -> error("Unknown fixture route $route")
    }

    private companion object {
        const val SOURCE = "import java.util.HashMap as NeverUsed\nclass Target { void proofMethod() {} }"
    }
}

internal class InvocationProofWire(scope: CoroutineScope, observe: (ParseRequest, ParseResult) -> ParseResult) {
    val documents = DocumentProvider()
    val compiler = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
        object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                val parsed = delegate.parse(request)
                var transferred = false
                try {
                    return observe(request, parsed).also { transferred = true }
                } finally {
                    if (!transferred) parsed.compilationUnit.classLoader.close()
                }
            }
        }
    })
    val ast = AstRequestHandler(compiler, CoreAstConverter(), NativeAstConverter(), documents, scope)
    val workspace = GroovyWorkspaceService(compiler, compiler.symbolIndexingService, scope)
    val cli = GroovyTextDocumentService(
        scope,
        compiler,
        GroovyTextDocumentServiceOptions(
            documentProvider = documents,
            serverConfiguration = ServerConfiguration(
                diagnosticConfig = DiagnosticConfig(disabledProviders = setOf("codenarc", "custom-rules")),
            ),
        ),
    )

    fun open(uri: URI, text: String) {
        compiler.onDocumentOpened(uri)
        documents.put(uri, text, DocumentVersion.of(1))
    }
}

internal suspend fun withInvocationProof(
    observe: (ParseRequest, ParseResult) -> ParseResult,
    verify: suspend InvocationProofWire.() -> Unit,
): Unit = supervisorScope {
    val harness = InvocationProofWire(this, observe)
    try {
        harness.verify()
    } finally {
        harness.compiler.shutdown()
    }
}
