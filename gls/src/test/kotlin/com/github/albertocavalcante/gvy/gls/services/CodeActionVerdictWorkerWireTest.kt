// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.test.parseGroovyVersion
import com.github.albertocavalcante.gvy.gls.version.GroovyVersionRange
import com.github.albertocavalcante.gvy.gls.worker.WorkerCapabilities
import com.github.albertocavalcante.gvy.gls.worker.WorkerConnector
import com.github.albertocavalcante.gvy.gls.worker.WorkerDescriptor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeActionVerdictWorkerWireTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `a degraded parse cannot recall a verdict learned by another worker`() {
        val project = SpockTableWireProject(workspace)
        val base = project.base
        val before = "import spock.lang.*\nabstract class BaseSpec extends Specification {}\n"
        Files.writeString(base, before)
        val documents = project.documents
        val uri = project.uri
        val sessions = mutableListOf<WorkerSession>()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { session ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        synchronized(sessions) { sessions.add(session) }
                        return session.parse(request)
                    }
                }
            },
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val service = project.service(scope, compilation)
        try {
            project.open(compilation)
            val params = project.params
            val control = service.codeAction(params).get(10, TimeUnit.SECONDS)
            assertTrue(control.any { it.right.title == "Align Spock data table" })
            val learned = synchronized(sessions) { sessions.last() }
            Files.writeString(base, before.replace("{}", "{ void m( }"))
            assertNotNull(documents.update(uri, project.next, DocumentVersion.of(8)))
            runBlocking { compilation.compile(uri, project.next) }
            fun assertDegraded() {
                val compiled = assertNotNull(compilation.getCompiledSnapshot(uri))
                assertTrue(compiled.parseResult.outcome.isDegraded)
                val module = assertNotNull(compiled.parseResult.ast)
                val owner = SpockSourceContext.from(module, isPartial = true).classes.single {
                    it.classNode.name ==
                        "TableSpec"
                }
                assertEquals(SpecificationStatus.UNCERTAIN, owner.status)
            }
            assertDegraded()
            fun hasTable() = service.codeAction(params).get(10, TimeUnit.SECONDS)
                .any { it.right.title == "Align Spock data table" }
            assertTrue(hasTable(), "the original worker must recall its clean verdict")
            val worker = WorkerDescriptor(
                id = "changed",
                supportedRange = GroovyVersionRange(parseGroovyVersion("1.0.0"), parseGroovyVersion("5.0.0")),
                capabilities = WorkerCapabilities(),
                connector = WorkerConnector.InProcess,
            )
            assertTrue(compilation.updateSelectedWorker(worker))
            runBlocking { compilation.compile(uri, project.next) }
            assertTrue(synchronized(sessions) { sessions.last() !== learned }, "a different worker must parse")
            assertDegraded()
            assertEquals(false, hasTable(), "a different worker never established the clean verdict")
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
