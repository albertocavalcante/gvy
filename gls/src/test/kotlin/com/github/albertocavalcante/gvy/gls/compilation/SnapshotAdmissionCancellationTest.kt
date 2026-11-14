// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.ExternalSourceRegistry
import com.github.albertocavalcante.gvy.gls.sources.SourceCommandService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.sources.SourceResolveParams
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotAdmissionCancellationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `definition cancellation stops snapshot admission before remaining roots`(): Unit = runBlocking {
        assertAdmissionStops(definition = true)
    }

    @Test
    fun `source command cancellation stops snapshot admission before remaining roots`(): Unit = runBlocking {
        assertAdmissionStops(definition = false)
    }

    private suspend fun assertAdmissionStops(definition: Boolean) {
        Fixture(root).use { fixture ->
            val compiled = fixture.compilation.compile(fixture.uri, fixture.source)
            assertTrue(compiled.isSuccess)
            val service = spyk(fixture.compilation)
            // Isolate the command's explicit snapshot boundary from ensureCompiled's own admission.
            coEvery { service.ensureCompiled(fixture.uri, any()) } returns compiled
            val probes = mutableListOf<Path>()
            supervisorScope {
                val request = async<Unit> {
                    val job = checkNotNull(currentCoroutineContext()[Job])
                    fixture.onProbe = { path ->
                        probes.add(path)
                        if (path == fixture.primary) job.cancel()
                    }
                    if (definition) {
                        DefinitionProvider(service)
                            .provideDefinitions(fixture.uri.toString(), Position(0, 14)).toList()
                    } else {
                        SourceCommandService(
                            service,
                            fixture.documents,
                            mockk<SourceNavigator>(relaxed = true),
                            ExternalSourceRegistry(root.resolve("views")),
                        ).resolve(SourceResolveParams(fixture.uri.toString(), Position(0, 14), documentVersion = 1))
                    }
                }
                try {
                    assertFailsWith<CancellationException> { request.await() }
                    assertTrue(fixture.primary in probes, "The request must cancel during actual source admission")
                    assertFalse(
                        probes.any { it in fixture.secondaryRoots },
                        "Cancellation must stop admission before probing the remaining selected roots",
                    )
                } finally {
                    request.cancelAndJoin()
                    fixture.onProbe = {}
                }
            }
        }
    }

    private class Fixture(root: Path) : AutoCloseable {
        private val sources = Files.createDirectory(root.resolve("src"))
        val secondaryRoots = List(3) { index -> Files.createDirectory(root.resolve("secondary-$index")) }
        val source = "class Money { String currency }"
        val primary: Path = Files.writeString(sources.resolve("Money.groovy"), source)
        val uri = primary.toUri()
        val documents = DocumentProvider().apply { put(uri, source, 1) }
        var onProbe: (Path) -> Unit = {}
        private val manager = WorkspaceManager(sourceExclusion = { path ->
            onProbe(path)
            false
        }).apply {
            updateWorkspaceModel(root, emptyList(), listOf(sources) + secondaryRoots)
        }
        private val loaders = PublishedParserLoaders()
        val compilation = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = manager,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also {
                        loaders.record(it.compilationUnit.classLoader)
                    }
                }
            },
        )

        override fun close() {
            compilation.shutdown()
            loaders.close()
        }
    }
}
