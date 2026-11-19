// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Type definition gets its AST context and the text its columns index into from one cache entry (#2218). */
class CompilationContextSnapshotTest {
    private val uri = URI.create("file:///context-snapshot.groovy")
    private val content = "class Foo {}\ndef f = new Foo()"

    @Test
    fun `context and source text come from the same snapshot, not separate cache reads`(): Unit = runBlocking {
        val service = spyk(GroovyCompilationService())
        assertTrue(service.compile(uri, content).isSuccess, "Compilation should succeed")
        val snapshot = assertNotNull(service.getCompiledSnapshot(uri))

        // A recompile lands after the first cache read: any later read would see another generation.
        val otherUri = URI.create("file:///other-generation.groovy")
        val other = GroovyCompilationService()
        val otherSource = "\n\n\nclass Foo {}"
        assertTrue(other.compile(otherUri, otherSource).isSuccess)
        val recompiled = CompiledDocumentSnapshot(otherSource, assertNotNull(other.getParseResult(otherUri)))
        every { service.getCompiledSnapshot(uri) } returnsMany listOf(snapshot, recompiled)

        val (context, text) = assertNotNull(service.createContextWithSource(uri))

        assertSame(snapshot.parseResult.astModel, context.astModel)
        assertSame(snapshot.parseResult.ast, context.moduleNode)
        assertEquals(content, text)
    }
}
