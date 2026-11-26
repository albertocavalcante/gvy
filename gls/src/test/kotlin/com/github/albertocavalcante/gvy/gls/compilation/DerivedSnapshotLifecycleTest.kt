// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.api.AnalysisCompilerPolicy
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseOutcome
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DerivedSnapshotLifecycleTest {
    enum class Finish { SUCCESS, PARTIAL, LAZY, FAILURE, CANCELLED, FATAL, RETIRED_AT_CLOSE, CANCELLED_AT_CLOSE }

    @Test
    fun `a cancelled worker throwing IO cannot become an ordinary failed query`(): Unit = runBlocking {
        val uri = URI.create("file:///Cancelled.groovy")
        val documents = DocumentProvider().apply { put(uri, "class Cancelled {}") }
        var cancelRequest: () -> Unit = { error("worker invoked before the request") }
        val worker = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                cancelRequest()
                throw IOException("worker stopped")
            }
        }
        val compilation = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { worker })
        var returned = false
        try {
            val source = compilation.captureSnapshotSource(uri)
            val query = async {
                val caller = currentCoroutineContext().job
                cancelRequest = { caller.cancel() }
                compilation.queryDerivedSnapshot(source, "class Copy {}") { error("worker failed before consumer") }
                returned = true
            }
            assertFailsWith<CancellationException> { query.await() }
            assertEquals(false, returned)
        } finally {
            compilation.shutdown()
        }
    }

    @ParameterizedTest
    @EnumSource(Finish::class)
    fun `a derived producer owns the loader through materialization and disposal`(finish: Finish): Unit = runBlocking {
        val uri = URI.create("file:///Owned.groovy")
        val original = "class Owned {}"
        val copy = "class Derived {}"
        val documents = DocumentProvider().apply { put(uri, original) }
        var atClose: () -> Unit = {}
        var closes = 0
        val loader = object : GroovyClassLoader(this@DerivedSnapshotLifecycleTest.javaClass.classLoader) {
            override fun close() {
                closes++
                super.close()
                atClose()
            }
        }
        val worker = object : WorkerSession {
            override fun parse(request: ParseRequest): ParseResult {
                assertEquals(copy, request.content)
                val configuration = CompilerConfiguration().also(AnalysisCompilerPolicy::apply)
                val unit = CompilationUnit(configuration, null, loader, loader)
                assertSame(loader, unit.classLoader)
                val source = unit.addSource(request.uri.path, request.content)
                unit.compile(request.compilePhase)
                val ast = assertNotNull(source.ast)
                val model = RecursiveAstVisitor(NodeRelationshipTracker()).apply { visitModule(ast, uri) }
                return ParseResult(ast.takeUnless { finish == Finish.PARTIAL }, unit, source, emptyList(),
                    SymbolTable().apply { buildFromVisitor(model) }, model, outcome = ParseOutcome(requestedPhase = request.compilePhase))
            }
        }
        val service = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { worker })
        val ordinary = IOException("query failed")
        val fatal = AssertionError("fatal query")
        try {
            val captured = service.captureSnapshotSource(uri)
            val job = async {
                val caller = currentCoroutineContext().job
                atClose = {
                    when (finish) {
                        Finish.RETIRED_AT_CLOSE -> service.clearCaches()
                        Finish.CANCELLED_AT_CLOSE -> {
                            caller.cancel()
                            throw ordinary
                        }
                        else -> Unit
                    }
                }
                runCatching {
                    service.queryDerivedSnapshot(captured, copy) { held ->
                        assertEquals(0, closes)
                        assertSame(loader, held.parseResult.compilationUnit.classLoader)
                        assertEquals(copy, held.sourceText)
                        when (finish) {
                            Finish.FAILURE -> throw ordinary
                            Finish.CANCELLED -> throw CancellationException("query cancelled")
                            Finish.FATAL -> throw fatal
                            Finish.LAZY -> sequenceOf("deferred")
                            else -> "eager"
                        }
                    }
                }
            }
            if (finish == Finish.CANCELLED_AT_CLOSE) {
                assertFailsWith<CancellationException> { job.await() }
            } else {
                val result = job.await()
                when (finish) {
                    Finish.FATAL -> assertSame(fatal, result.exceptionOrNull())
                    Finish.CANCELLED -> assertIs<CancellationException>(result.exceptionOrNull())
                    Finish.FAILURE -> assertSame(ordinary, assertIs<SnapshotQuery.Failure>(result.getOrThrow()).cause)
                    Finish.LAZY -> assertIs<IllegalStateException>(assertIs<SnapshotQuery.Failure>(result.getOrThrow()).cause)
                    Finish.RETIRED_AT_CLOSE -> assertSame(SnapshotQuery.Stale, result.getOrThrow())
                    else -> {
                        val answer = assertIs<SnapshotQuery.Answer<*>>(result.getOrThrow())
                        assertEquals("eager", answer.value)
                        assertEquals(finish == Finish.PARTIAL, answer.partial)
                    }
                }
            }
            assertEquals(1, closes)
            assertNull(service.getCompiledSnapshot(uri), "a transient copy must never publish into the cache")
        } finally {
            service.shutdown()
            if (closes == 0) loader.close()
        }
    }
}
