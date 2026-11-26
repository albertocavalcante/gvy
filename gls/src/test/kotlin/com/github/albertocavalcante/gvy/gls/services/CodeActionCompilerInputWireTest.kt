// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SnapshotAcquisition
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeActionCompilerInputWireTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `source changed after worker parsed it cannot certify a Spock table`() {
        val project = SpockTableWireProject(workspace)
        val before = "import spock.lang.Specification\nabstract class BaseSpec extends Specification {}\n"
        // Keep the written extends spelling identical while changing the class it binds to.
        val after = "class Specification {}\nabstract class BaseSpec extends Specification {}\n"
        Files.writeString(project.base, before)
        val observed = AtomicReference<ParseResult?>()
        val compilation = GroovyCompilationService(
            documentProvider = project.documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        if (request.uri == project.uri && request.content == project.next) {
                            assertTrue(observed.compareAndSet(null, parsed), "wire request reparsed the changed source")
                            changeBase(parsed, project, before, after)
                        }
                        return parsed
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val service = project.service(scope, compilation)
        try {
            project.open(compilation)
            val control = service.codeAction(project.params).get(10, TimeUnit.SECONDS)
            assertTrue(control.any { it.right.title == "Align Spock data table" })
            assertNotNull(project.documents.update(project.uri, project.next, DocumentVersion.of(8)))
            val acquired = runBlocking { compilation.acquireEditorSnapshot(project.uri) }
            val held = assertIs<SnapshotAcquisition.Available>(acquired).snapshot
            assertSame(assertNotNull(observed.get()), held.parseResult, "the held producer must be the mutated parse")
            assertEquals(after, Files.readString(project.base))
            assertEquals(project.next, held.sourceText)
            val owner = assertNotNull(held.parseResult.ast?.classes?.singleOrNull { it.name == "TableSpec" })
            assertEquals("spock.lang.Specification", owner.superClass.redirect().superClass.redirect().name)
            val failure = assertThrows<ExecutionException> { service.codeAction(project.params).get(10, TimeUnit.SECONDS) }
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                assertIs<ResponseErrorException>(failure.cause).responseError.code,
            )
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun changeBase(parsed: ParseResult, project: SpockTableWireProject, before: String, after: String) {
        assertTrue(parsed.outcome.achievedPhase >= Phases.CANONICALIZATION)
        val owner = assertNotNull(parsed.ast?.classes?.singleOrNull { it.name == "TableSpec" })
        val parent = owner.superClass.redirect()
        assertEquals("BaseSpec", parent.name)
        assertFalse(parent.isResolved)
        assertNotNull(parent.module?.context)
        assertEquals("spock.lang.Specification", parent.superClass.redirect().name)
        assertEquals(before, Files.readString(project.base))
        Files.writeString(project.base, after)
    }
}
