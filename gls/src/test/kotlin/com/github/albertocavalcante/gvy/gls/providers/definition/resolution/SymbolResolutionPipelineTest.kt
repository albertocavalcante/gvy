// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pipeline rule: the first success wins; with no success, the FIRST strategy that threw is the result (a
 * [ResolutionError] whose cause is the throwable), so a later strategy's ordinary miss cannot hide a bug;
 * with no throw, the last miss is returned. Cancellation and errors propagate.
 */
class SymbolResolutionPipelineTest {
    private val context = ResolutionContext(ClassNode("Any", 0, null), URI.create("file:///Any.groovy"), Position(0, 0))
    private val hit = DefinitionResolver.DefinitionResult.Binary(URI.create("file:///Hit.java"), "Hit")

    private fun resolve(vararg strategies: SymbolResolutionStrategy): ResolutionResult =
        runBlocking { SymbolResolutionStrategy.pipeline(*strategies).resolve(context) }

    private fun throwing(error: Throwable) = SymbolResolutionStrategy { throw error }

    private val miss = SymbolResolutionStrategy { SymbolResolutionStrategy.notFound("nothing here", "miss") }

    @Test
    fun `an earlier throw is not hidden by a later strategy's miss`() {
        val boom = IOException("first")
        val result = resolve(throwing(boom), throwing(IllegalStateException("second")), miss)

        val error = result.leftOrNull()
        assertSame(boom, error?.cause)
        assertTrue(error?.reason.orEmpty().contains("first"))
    }

    @Test
    fun `a later success wins over an earlier throw`() {
        val result =
            resolve(throwing(IOException("ignored")), SymbolResolutionStrategy { SymbolResolutionStrategy.found(hit) })

        assertEquals(hit, result.getOrNull())
    }

    @Test
    fun `misses alone return the last miss with no cause`() {
        val result = resolve(SymbolResolutionStrategy { SymbolResolutionStrategy.notFound("first", "a") }, miss)

        val error = result.leftOrNull()
        assertEquals("nothing here", error?.reason)
        assertNull(error?.cause)
    }

    @Test
    fun `cancellation propagates`() {
        assertFailsWith<CancellationException> { resolve(throwing(CancellationException("cancelled")), miss) }
    }

    @Test
    fun `an error propagates`() {
        assertFailsWith<StackOverflowError> { resolve(throwing(StackOverflowError()), miss) }
    }
}
