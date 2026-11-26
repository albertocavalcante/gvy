// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.ImplementationFacts
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReferenceCancellationTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `producer cancellation wins over a readiness callback failure`(repair: Boolean): Unit = runBlocking {
        val origin = URI.create("file:///Example.groovy")
        val text = "class Example {}"
        val source = mockk<SnapshotSource> {
            every { uri } returns origin
            every { documents } returns mapOf(origin to DocumentSnapshot(text, null))
            every { workspace } returns mockk { every { sources } returns emptyList() }
            every { isCurrent() } returns true
        }
        var observed = false
        var invoked = false
        val request = async {
            val caller = currentCoroutineContext()
            val index = WorkspaceSymbolIndex(GroovySemanticDB())
            val stored = index.storeDocument(
                origin, SemanticDocument(origin, emptyList(), emptyList()), text, ImplementationFacts.empty,
            )
            val producer = mockk<ReferenceProducer> {
                every { complete } returns true
                every { isCurrent(source, any(), any(), any()) } answers {
                    invoked = true
                    caller.cancel()
                    throw IOException("cancelled producer callback")
                }
            }
            val readiness = ReferenceReadiness(
                source, index,
                currentPublication = { ReferencePublication(stored, Phases.SEMANTIC_ANALYSIS, false, producer) },
                selectSources = { _, model -> model.sources },
            )
            try {
                if (repair) {
                    readiness.prepareOpenSources(mockk())
                } else {
                    readiness.query { error("cancelled coverage must not compute") }
                }
            } catch (_: CancellationException) {
                observed = true
            }
        }
        assertFailsWith<CancellationException> { request.await() }
        request.join()
        assertTrue(invoked)
        assertTrue(observed, "callback cancellation must win before returning to its caller")
    }

    @ParameterizedTest
    @CsvSource("1, true", "1, false", "2, true", "2, false", "3, true", "3, false")
    fun `cancellation in a shadow check wins over stale and current results`(stage: Int, stillCurrent: Boolean): Unit =
        runBlocking {
            val origin = URI.create("file:///Example.groovy")
            val source = "class Example {}"
            val capture = mockk<SnapshotSource> {
                every { uri } returns origin
                every { documents } returns mapOf(origin to DocumentSnapshot(source, null))
                every { workspace } returns mockk { every { sources } returns emptyList() }
                every { isCurrent() } returns true
            }
            var computed = false
            var observed = false
            val request = async {
                val caller = currentCoroutineContext()
                // Readiness captures shadows once before preparation, then checks them around coverage and computation.
                var checks = -1
                val index = WorkspaceSymbolIndex(GroovySemanticDB()) {
                    checks++
                    if (checks == stage) caller.cancel()
                    if (checks == stage && !stillCurrent) setOf(origin) else emptySet()
                }
                val doc = SemanticDocument(origin, emptyList(), emptyList())
                val stored = index.storeDocument(origin, doc, source, ImplementationFacts.empty)
                val producer = mockk<ReferenceProducer> {
                    every { complete } returns true
                    every { isCurrent(capture, any(), any(), any()) } returns true
                }
                val readiness = ReferenceReadiness(
                    capture,
                    index,
                    currentPublication = {
                        ReferencePublication(stored, Phases.SEMANTIC_ANALYSIS, false, producer)
                    },
                    selectSources = { _, model -> model.sources },
                )
                try {
                    readiness.query {
                        computed = true
                        emptyList<String>()
                    }
                } catch (_: CancellationException) {
                    observed = true
                }
            }
            assertFailsWith<CancellationException> { request.await() }
            request.join()
            assertTrue(observed, "query must throw before returning to the cancelled caller")
            assertEquals(stage == 3, computed)
        }
}
