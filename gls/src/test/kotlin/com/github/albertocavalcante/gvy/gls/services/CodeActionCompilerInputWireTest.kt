// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
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
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeActionCompilerInputWireTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `source changed after worker parsed it cannot certify a Spock table`() {
        val src = Files.createDirectories(workspace.resolve("src"))
        val base = src.resolve("BaseSpec.groovy")
        val before = "import spock.lang.Specification\nabstract class BaseSpec extends Specification {}\n"
        // Keep the written extends spelling identical while changing the class it binds to.
        val after = "class Specification {}\nabstract class BaseSpec extends Specification {}\n"
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
        val changed = AtomicBoolean()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        val parsed = delegate.parse(request)
                        if (request.uri == uri && request.content == next && changed.compareAndSet(false, true)) {
                            assertTrue(parsed.outcome.achievedPhase >= Phases.CANONICALIZATION)
                            val owner = assertNotNull(parsed.ast?.classes?.singleOrNull { it.name == "TableSpec" })
                            val parent = owner.superClass.redirect()
                            assertEquals("BaseSpec", parent.name)
                            assertFalse(parent.isResolved)
                            assertNotNull(parent.module?.context)
                            assertEquals("spock.lang.Specification", parent.superClass.redirect().name)
                            assertEquals(before, Files.readString(base))
                            Files.writeString(base, after)
                        }
                        return parsed
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
            assertNotNull(documents.update(uri, next, DocumentVersion.of(8)))
            runBlocking { compilation.compile(uri, next) }
            assertTrue(changed.get(), "actual worker must run before the request")
            assertEquals(after, Files.readString(base))
            val held = assertNotNull(compilation.getCompiledSnapshot(uri))
            assertEquals(next, held.sourceText)
            val owner = assertNotNull(held.parseResult.ast?.classes?.singleOrNull { it.name == "TableSpec" })
            assertEquals("spock.lang.Specification", owner.superClass.redirect().superClass.redirect().name)
            val failure = assertThrows<ExecutionException> { service.codeAction(params).get(10, TimeUnit.SECONDS) }
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                assertIs<ResponseErrorException>(failure.cause).responseError.code,
            )
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
