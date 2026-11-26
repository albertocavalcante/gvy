// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.setTestClasspath
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrossFileDefinitionOwnershipTest {
    enum class Change { NONE, WORKER, CLASSPATH_DURING_SOURCE_FAILURE }

    @ParameterizedTest
    @EnumSource(Change::class)
    fun `definition validates the producer after JDK source navigation`(change: Change, @TempDir root: Path) {
        val origin = root.resolve("Imports.groovy").toUri()
        val text = "import java.util.ArrayList\nclass Imports {}\n"
        val sourceFile = Files.writeString(root.resolve("ArrayList.java"), "package java.util;\nclass ArrayList {}\n")
        val paths = AtomicReference<List<Path>>(emptyList())
        val documents = DocumentProvider().apply { put(origin, text) }
        val parses = CopyOnWriteArrayList<Pair<ParseRequest, ParseResult>>()
        val compilation = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { native ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = native.parse(request).also {
                    if (request.uri == origin) parses.add(request to it)
                }
            }
        })
        val reached = CompletableFuture<Pair<URI, String>>()
        val release = CompletableDeferred<Unit>()
        val navigator = object : SourceNavigator by SourceNavigationService() {
            override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult {
                reached.complete(classpathUri to className)
                release.await()
                if (change == Change.CLASSPATH_DURING_SOURCE_FAILURE) throw IOException("attachment read failed")
                if (change == Change.WORKER) {
                    return SourceNavigator.SourceResult.BinaryOnly(classpathUri, className, "no source")
                }
                return SourceNavigator.SourceResult.SourceLocation(sourceFile.toUri(), className, 2)
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.workspaceManager.setTestClasspath(root, paths::get)
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents, sourceNavigator = navigator)
            val service = GroovyTextDocumentService(scope, compilation, options)
            val at = Position(0, text.indexOf("ArrayList"))
            val params = DefinitionParams(TextDocumentIdentifier(origin.toString()), at)
            val answer = service.definition(params)
            val (binary, name) = reached.get(15, TimeUnit.SECONDS)
            assertEquals("java.util.ArrayList", name)
            assertEquals(URI.create("jrt:/java.base/java/util/ArrayList.class"), binary)
            assertNativeImport(parses, text, name)
            when (change) {
                Change.NONE -> Unit
                Change.WORKER -> {
                    replaceWorker(compilation)
                }
                Change.CLASSPATH_DURING_SOURCE_FAILURE -> {
                    val fingerprint = compilation.workspaceManager.fingerprintFor(origin)
                    paths.set(listOf(Files.createDirectory(root.resolve("new-classpath"))))
                    assertEquals(fingerprint, compilation.workspaceManager.fingerprintFor(origin))
                    val selected = compilation.workspaceManager.compileContextFor(origin, text)
                    assertEquals(paths.get(), selected.classpath)
                }
            }
            release.complete(Unit)

            if (change == Change.NONE) {
                val target = Location(sourceFile.toUri().toString(), Range(Position(1, 0), Position(1, 0)))
                assertEquals(listOf(target), answer.get(15, TimeUnit.SECONDS).left)
            } else {
                val failure = assertThrows<ExecutionException> { answer.get(15, TimeUnit.SECONDS) }
                val error = assertIs<ResponseErrorException>(failure.cause).responseError
                assertEquals(ResponseErrorCode.ContentModified.value, error.code)
            }
        } finally {
            release.complete(Unit)
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun assertNativeImport(parses: List<Pair<ParseRequest, ParseResult>>, text: String, name: String) {
        val (request, parsed) = parses.last { it.first.compilePhase == Phases.CANONICALIZATION }
        assertEquals(text, request.content)
        assertEquals(ParseMode.WORKSPACE, request.parseMode)
        assertEquals(emptyList(), request.classpath)
        val bound = assertNotNull(parsed.ast).imports.single { it.className == name }.type.redirect()
        assertTrue(bound.isResolved)
        assertFalse(bound.isPrimaryClassNode)
    }

    private fun replaceWorker(compilation: GroovyCompilationService) {
        val worker = WorkerDescriptor(
            id = "definition-replacement",
            supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("5.0.0")),
            capabilities = WorkerCapabilities(),
            connector = WorkerConnector.InProcess,
        )
        assertTrue(compilation.updateSelectedWorker(worker))
        assertEquals(worker, compilation.getSelectedWorker())
    }
}
