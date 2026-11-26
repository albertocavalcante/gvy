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
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.Phases
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
        val project = SpockTableWireProject(workspace)
        val base = project.base
        val before = "import spock.lang.*\nabstract class BaseSpec extends Specification {}\n"
        Files.writeString(base, before)
        val documents = project.documents
        val uri = project.uri
        val compilation = GroovyCompilationService(documentProvider = documents)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pause = AtomicBoolean(false)
        val service = project.service(
            scope,
            compilation,
            Formatter { text ->
                if (pause.get()) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                text
            },
        )
        try {
            project.open(compilation)
            val compiled = assertNotNull(compilation.getCompiledSnapshot(uri))
            assertEquals(project.source, compiled.sourceText)
            assertTrue(compiled.parseResult.outcome.achievedPhase >= Phases.CANONICALIZATION)
            val module = assertNotNull(compiled.parseResult.ast)
            assertSourceBase(module)
            val params = project.params
            val control = service.codeAction(params).get(10, TimeUnit.SECONDS)
            assertTrue(control.any { it.right.title == "Align Spock data table" }, "table action precondition")
            val nextSource = project.next
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

    private fun assertSourceBase(module: ModuleNode) {
        val baseNode = assertNotNull(module.classes.firstOrNull { it.name == "TableSpec" }?.superClass?.redirect())
        assertEquals("BaseSpec", baseNode.name)
        assertFalse(baseNode.isResolved, "a resolved JVM base does not read the source witness")
        assertNotNull(baseNode.module?.context?.name, "the source base must have a locator")
    }
}
