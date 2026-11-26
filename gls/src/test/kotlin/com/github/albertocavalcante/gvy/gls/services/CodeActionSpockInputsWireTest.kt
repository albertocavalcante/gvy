// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.source.DocumentVersion
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeActionSpockInputsWireTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `changed base after verdict consumption rejects table action`() {
        val src = Files.createDirectories(workspace.resolve("src"))
        val base = src.resolve("BaseSpec.groovy")
        Files.writeString(base, "import spock.lang.*\nabstract class BaseSpec extends Specification {}\n")
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
        val uri = file.toUri()
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pause = AtomicBoolean(false)
        val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(
                documentProvider = documents,
                formatter = Formatter { text ->
                    if (pause.get()) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    text
                },
            ),
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
            val compiled = assertNotNull(compilation.getCompiledSnapshot(uri))
            assertEquals(source, compiled.sourceText)
            assertTrue(compiled.parseResult.outcome.achievedPhase >= Phases.CANONICALIZATION)
            val module = assertNotNull(compiled.parseResult.ast)
            val baseNode = assertNotNull(module.classes.firstOrNull { it.name == "TableSpec" }?.superClass?.redirect())
            assertEquals("BaseSpec", baseNode.name)
            assertFalse(baseNode.isResolved, "a resolved JVM base does not read the source witness")
            assertNotNull(baseNode.module?.context?.name, "the source base must have a locator")
            val params = CodeActionParams(
                TextDocumentIdentifier(uri.toString()),
                Range(Position(5, 0), Position(6, 10)),
                CodeActionContext(emptyList()).apply { only = listOf(CodeActionKind.RefactorRewrite) },
            )
            val control = service.codeAction(params).get(10, TimeUnit.SECONDS)
            assertTrue(control.any { it.right.title == "Align Spock data table" }, "table action precondition")
            val nextSource = "$source\n// next version"
            assertNotNull(documents.update(uri, nextSource, DocumentVersion.of(8)))
            runBlocking { compilation.compile(uri, nextSource) }
            val next = assertNotNull(compilation.getCompiledSnapshot(uri))
            assertEquals(nextSource, next.sourceText)
            assertNotNull(next.parseResult.ast)
            assertTrue(next.parseResult.ast !== module, "new parse must refresh verdict memory")
            assertTrue(next.parseResult.outcome.achievedPhase >= Phases.CANONICALIZATION)
            pause.set(true)
            val answer = service.codeAction(params)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "request did not pass Spock generation")
            Files.writeString(base, "import spock.lang.*\nabstract class BaseSpec extends Object {}\n")
            release.countDown()
            val error = assertThrows<ExecutionException> { answer.get(10, TimeUnit.SECONDS) }
            assertEquals(
                ResponseErrorCode.ContentModified.value,
                assertIs<ResponseErrorException>(error.cause).responseError.code,
            )
        } finally {
            release.countDown()
            scope.cancel()
            compilation.shutdown()
        }
    }
}
