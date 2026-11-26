// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClosedReferenceInventoryTest {
    @Test
    fun `a closed reference producer follows the actual opened companion before it can answer`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val origin = root.resolve("Origin.groovy")
        val closed = root.resolve("UsesOrigin.groovy")
        val disk = "class Origin { def diskOnly() {} }"
        val overlay = "class Origin { def bufferOnly() {} }"
        Files.writeString(origin, disk)
        Files.writeString(closed, "class UsesOrigin extends Origin {}")
        val documents = DocumentProvider()
        val bindings = CopyOnWriteArrayList<String>()
        val compilation = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { worker ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = worker.parse(request).also { result ->
                    if (request.uri == closed.toUri()) {
                        assertTrue(result.isSuccessful && !result.outcome.isDegraded)
                        assertTrue(result.outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
                        val parent = assertNotNull(result.ast).classes.single().superClass.redirect()
                        val physical = result.compilationUnit.ast.modules.flatMap { it.classes }.single {
                            it.name ==
                                "Origin"
                        }
                        assertSame(physical, parent)
                        bindings.add(parent.module.context.source.reader.use { it.readText() })
                    }
                }
            }
        })
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.getWorkspaceCompiler().compileWorkspace()
            assertEquals(listOf(disk), bindings.toList())
            val before = compilation.referenceReadiness(origin.toUri()).query { it.index.visibleDocuments }
            assertEquals(setOf(origin.toUri(), closed.toUri()), assertIs<SnapshotQuery.Answer<*>>(before).value)

            documents.put(origin.toUri(), overlay)
            compilation.onDocumentOpened(origin.toUri())
            compilation.compile(origin.toUri(), overlay)
            val readiness = compilation.referenceReadiness(origin.toUri())
            val stale = assertIs<SnapshotQuery.Failure>(readiness.query { error("Stale disk bindings") })
            assertEquals(
                "Reference index unavailable for ${closed.toUri()}",
                assertIs<ResponseErrorException>(stale.cause).message,
            )
            readiness.prepareOpenSources(compilation)
            assertEquals(
                listOf(disk, overlay),
                bindings.toList(),
                "The closed file must be compiled against the actual open companion",
            )
            val healthy = readiness.query { it.index.visibleDocuments }
            assertEquals(setOf(origin.toUri(), closed.toUri()), assertIs<SnapshotQuery.Answer<*>>(healthy).value)

            readiness.prepareOpenSources(compilation)
            assertEquals(
                listOf(disk, overlay),
                bindings.toList(),
                "A current closed publication needs no new compiler work",
            )
        } finally {
            compilation.shutdown()
        }
    }
}
