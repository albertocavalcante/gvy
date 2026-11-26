// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.gls.worker.defaultWorkerDescriptors
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AstProducerRetirementTest {
    enum class Finish { CURRENT, WORKER, WORKSPACE, CLASSPATH, CANCELLED }

    @ParameterizedTest
    @EnumSource(Finish::class)
    fun `AST response follows the real transient parser through delivery and loader release`(
        finish: Finish,
        @TempDir root: Path,
    ): Unit = runBlocking {
        val uri = root.resolve("Inspection.groovy").toUri()
        val source = "class Inspection { String currentName }"
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(7)) }
        val document = assertNotNull(documents.getSnapshot(uri))
        val owner = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + owner)
        val release = CountDownLatch(1)
        val completedParse = CompletableFuture<ParseResult>()
        val marker = Files.createTempFile(root, "parse-loader-", ".txt").fileName.toString()
        val compiler = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    val parsed = delegate.parse(request)
                    parsed.compilationUnit.classLoader.addClasspath(root.toString())
                    completedParse.complete(parsed)
                    check(release.await(30, TimeUnit.SECONDS)) { "AST parser was never released" }
                    return parsed
                }
            }
        })
        try {
            compiler.updateWorkspaceModel(root, emptyList(), listOf(root))
            val handler = AstRequestHandler(compiler, CoreAstConverter(), NativeAstConverter(), documents, scope)
            val pending = handler.getAst(AstParams(uri.toString(), "native", document.contentHash))
            val parsed = completedParse.get(30, TimeUnit.SECONDS)
            assertSame(parsed.ast, parsed.sourceUnit.ast)
            assertTrue(
                parsed.sourceUnit.phase > Phases.CANONICALIZATION ||
                    (parsed.sourceUnit.phase == Phases.CANONICALIZATION && parsed.sourceUnit.isPhaseComplete),
            )
            assertNotNull(parsed.compilationUnit.classLoader.getResource(marker))
            assertFalse(pending.isDone)
            AstFinish.apply(finish, compiler, root, owner)
            assertSame(document, documents.getSnapshot(uri))
            release.countDown()
            AstFinish.verify(finish, pending, document.contentHash)()
            owner.cancelAndJoin()
            assertNull(parsed.compilationUnit.classLoader.getResource(marker), "Request-owned loader stayed open")
            assertNull(compiler.getCompiledSnapshot(uri), "AST inspection must remain unpublished")
        } finally {
            release.countDown()
            owner.cancelAndJoin()
            compiler.shutdown()
        }
    }
}

private object AstFinish {
    fun apply(
        finish: AstProducerRetirementTest.Finish,
        compiler: GroovyCompilationService,
        root: Path,
        owner: CompletableJob,
    ) {
        when (finish) {
            AstProducerRetirementTest.Finish.WORKER -> {
                val worker = defaultWorkerDescriptors().single().copy(id = "inspection-replacement")
                assertTrue(compiler.updateSelectedWorker(worker))
                assertEquals(worker, compiler.getSelectedWorker())
            }
            AstProducerRetirementTest.Finish.WORKSPACE -> {
                val otherRoot = Files.createDirectory(root.resolve("replacement-workspace"))
                compiler.updateWorkspaceModel(otherRoot, emptyList(), listOf(otherRoot))
                assertEquals(otherRoot, compiler.workspaceManager.current().root)
            }
            AstProducerRetirementTest.Finish.CLASSPATH -> {
                val dependency = Files.createDirectory(root.resolve("new-dependency"))
                compiler.updateWorkspaceModel(root, listOf(dependency), listOf(root))
                assertTrue(dependency in compiler.workspaceManager.current().classpath)
            }
            AstProducerRetirementTest.Finish.CANCELLED -> owner.cancel()
            AstProducerRetirementTest.Finish.CURRENT -> Unit
        }
    }

    fun verify(
        finish: AstProducerRetirementTest.Finish,
        pending: CompletableFuture<AstResult>,
        contentHash: String,
    ): suspend () -> Unit = when (finish) {
        AstProducerRetirementTest.Finish.CURRENT -> suspend {
            val response = pending.await()
            val dto = Json.decodeFromString<AstNodeDto>(response.ast)
            assertTrue(
                dto.children.single { it.properties["name"] == "Inspection" }
                    .children.any { it.properties["name"] == "currentName" },
            )
            assertEquals(contentHash, response.sourceHash)
            assertEquals(7, response.documentVersion)
        }
        AstProducerRetirementTest.Finish.CANCELLED -> suspend {
            assertFailsWith<CancellationException> { pending.await() }
        }
        else -> suspend {
            val stale = assertFailsWith<ResponseErrorException> { pending.await() }
            assertEquals(ResponseErrorCode.ContentModified.value, stale.responseError.code)
        }
    }
}
