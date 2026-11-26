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
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
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
        val src = Files.createDirectories(workspace.resolve("src"))
        val base = src.resolve("BaseSpec.groovy")
        val before = "import spock.lang.*\nabstract class BaseSpec extends Specification {}\n"
        Files.writeString(base, before)
        val file = src.resolve("TableSpec.groovy")
        val source = """
            class TableSpec extends BaseSpec {
                def "feature"() {
                    expect:
                    true
                    where:
                    a | b
                    100|2
                }
            }
        """.trimIndent()
        Files.writeString(file, source)
        val next = "$source\n// next version"
        val uri = file.toUri()
        val documents = DocumentProvider()
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
        val service = GroovyTextDocumentService(
            scope, compilation, GroovyTextDocumentServiceOptions(documentProvider = documents, formatter = Formatter { it }),
        )
        try {
            fun jar(type: Class<*>) = Path.of(type.protectionDomain.codeSource.location.toURI())
            compilation.updateWorkspaceModel(
                workspace,
                listOf(jar(spock.lang.Specification::class.java), jar(groovy.lang.GroovyObject::class.java)),
                listOf(src),
            )
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(7))
            runBlocking { compilation.compile(uri, source) }
            val params = CodeActionParams(
                TextDocumentIdentifier(uri.toString()),
                Range(Position(5, 0), Position(6, 10)),
                CodeActionContext(emptyList()).apply { only = listOf(CodeActionKind.RefactorRewrite) },
            )
            val control = service.codeAction(params).get(10, TimeUnit.SECONDS)
            assertTrue(control.any { it.right.title == "Align Spock data table" })
            val learned = synchronized(sessions) { sessions.last() }
            Files.writeString(base, before.replace("{}", "{ void m( }"))
            assertNotNull(documents.update(uri, next, DocumentVersion.of(8)))
            runBlocking { compilation.compile(uri, next) }
            fun assertDegraded() {
                val compiled = assertNotNull(compilation.getCompiledSnapshot(uri))
                assertTrue(compiled.parseResult.outcome.isDegraded)
                val module = assertNotNull(compiled.parseResult.ast)
                val owner = SpockSourceContext.from(module, isPartial = true).classes.single { it.classNode.name == "TableSpec" }
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
            runBlocking { compilation.compile(uri, next) }
            assertTrue(synchronized(sessions) { sessions.last() !== learned }, "a different worker must parse")
            assertDegraded()
            assertEquals(false, hasTable(), "a different worker never established the clean verdict")
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
