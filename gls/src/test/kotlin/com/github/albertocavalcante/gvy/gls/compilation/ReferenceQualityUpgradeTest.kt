// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReferenceQualityUpgradeTest {
    @Test
    fun `healthy same-phase recompilation restores complete references and survives a later degraded parse`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val support = root.resolve("Support.groovy")
        val healthy = "class Support {}"
        Files.writeString(support, healthy)
        val uri = root.resolve("Subject.groovy").toUri()
        val text = "class Subject { def greet() {} }"
        val documents = DocumentProvider().apply { put(uri, text) }
        val invocations = mutableListOf<ParseRequest>()
        var degrade = true
        val compilation =
            GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { worker ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult {
                        if (request.uri != uri) return worker.parse(request)
                        invocations.add(request)
                        if (degrade) Files.writeString(support, "class Support { void broken( }")
                        return try {
                            worker.parse(request)
                        } finally {
                            Files.writeString(support, healthy)
                        }
                    }
                }
            })
        try {
            compilation.updateWorkspaceModel(root, emptyList(), listOf(root))
            compilation.getWorkspaceCompiler().compileWorkspace()
            suspend fun publish(degraded: Boolean) {
                degrade = degraded
                // Evict only the completed parse. Preserve the published semantic facts and input generation.
                compilation.compilationCacheService.invalidate(uri)
                compilation.compile(uri, text, Phases.SEMANTIC_ANALYSIS)
                val result = assertNotNull(compilation.getParseResult(uri))
                assertEquals(Phases.SEMANTIC_ANALYSIS, result.outcome.achievedPhase)
                assertEquals(degraded, result.outcome.isDegraded)
                assertTrue(result.ast != null)
                assertTrue(support in invocations.last().workspaceSources)
                val first = invocations.first()
                val latest = invocations.last()
                assertEquals(first.content, latest.content)
                assertEquals(first.sourceRoots, latest.sourceRoots)
                assertEquals(first.workspaceSources, latest.workspaceSources)
                assertEquals(first.classpath, latest.classpath)
                assertEquals(first.openSources, latest.openSources)
            }
            val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
            publish(true)
            assertNotNull(index.getDocument(uri))
            assertIs<SnapshotQuery.Failure>(
                compilation.referenceReadiness(uri).query {
                    error("degraded reference view")
                },
            )
            publish(false)
            val current = compilation.referenceReadiness(uri).query { it.index.getDocumentSnapshot(uri)?.document }
            val resolved = assertNotNull(assertIs<SnapshotQuery.Answer<*>>(current).value)
            publish(true)
            val preserved = compilation.referenceReadiness(uri).query {
                it.index.getDocumentSnapshot(uri)?.document
            }
            assertSame(resolved, assertIs<SnapshotQuery.Answer<*>>(preserved).value)
            assertEquals(3, invocations.size)
        } finally {
            compilation.shutdown()
        }
    }
}
