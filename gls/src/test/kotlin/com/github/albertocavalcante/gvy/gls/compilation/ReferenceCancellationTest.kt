// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
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
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReferenceCancellationTest {
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
                index.updateDocument(origin, SemanticDocument(origin, emptyList(), emptyList()), source)
                val producer = mockk<ReferenceProducer> {
                    every { complete } returns true
                    every { isCurrent(capture, any(), any(), any()) } returns true
                }
                val readiness = ReferenceReadiness(
                    capture,
                    index,
                    currentPublication = {
                        ReferencePublication(Phases.SEMANTIC_ANALYSIS, diskConfirmed = false, producer = producer)
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
