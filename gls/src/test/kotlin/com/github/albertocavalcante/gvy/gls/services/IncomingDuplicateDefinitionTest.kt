// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotQuery
import com.github.albertocavalcante.nativeapi.ParseMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IncomingDuplicateDefinitionTest {
    @Test
    fun `incoming calls for one physical target cannot include callers of its duplicate`(@TempDir root: Path): Unit =
        runBlocking {
            fun writeApi(side: String): URI {
                val file = Files.createDirectory(root.resolve(side)).resolve("Api.groovy")
                Files.writeString(file, "package p; class Api { def target() {}; def ${side}Caller() { target() } }")
                return file.toUri()
            }
            val left = writeApi("left")
            val right = writeApi("right")
            val documents = DocumentProvider()
            val inventory = documents.captureDocuments()
            val compilation = GroovyCompilationService(documentProvider = documents)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents)
            val service = GroovyTextDocumentService(scope, compilation, options)
            try {
                assertNotNull(compilation.ensureCompiled(left))
                val position = Position(0, Files.readString(Path.of(left)).indexOf("target"))
                fun prepare() = service.prepareCallHierarchy(
                    CallHierarchyPrepareParams(TextDocumentIdentifier(left.toString()), position),
                ).get(10, TimeUnit.SECONDS).single()
                fun incoming() = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(prepare()))
                    .get(10, TimeUnit.SECONDS).map { it.from.uri to it.from.name }
                val expected = listOf(left.toString() to "leftCaller")
                assertEquals(expected, incoming(), "the single physical definition resolves normally")

                assertNotNull(compilation.ensureCompiled(right))
                val files = setOf(left, right)
                for (origin in files) {
                    // Each closed file qualifies as a non-origin in one direction, requiring disk confirmation.
                    val checked = compilation.referenceReadiness(origin).query { references ->
                        assertEquals(files, references.index.visibleDocuments)
                        val targets = files.map { uri ->
                            assertEquals(ParseMode.MINIMAL, references.modeFor(uri))
                            val owned = compilation.acquireSnapshot(uri, mode = ParseMode.MINIMAL)
                            val held = assertIs<SnapshotAcquisition.Ready>(owned)
                            assertTrue(held.isCurrent())
                            assertTrue(references.matches(uri, held.snapshot))
                            val document = assertNotNull(references.index.getDocumentSnapshot(uri)).document
                            document.symbols.single { it.name == "target" }.symbol
                        }
                        val target = targets.distinct().single()
                        assertEquals(files, references.index.findReferenceDocuments(target).keys)
                    }
                    assertIs<SnapshotQuery.Answer<Unit>>(checked)
                }
                assertSame(inventory, documents.captureDocuments())

                val item = prepare()
                val request = service.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                try {
                    val calls = request.get(10, TimeUnit.SECONDS)
                    assertEquals(expected, calls.map { it.from.uri to it.from.name })
                } catch (failure: ExecutionException) {
                    val response = assertIs<ResponseErrorException>(failure.cause).responseError
                    assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
                }
            } finally {
                scope.cancel()
                compilation.shutdown()
            }
        }
}
