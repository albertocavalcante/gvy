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

        // Another generation of the same document, returned by the separate accessor a recompile could change.
        val otherUri = URI.create("file:///other-generation.groovy")
        val other = GroovyCompilationService()
        assertTrue(other.compile(otherUri, "\n\n\nclass Foo {}").isSuccess)
        every { service.getParseResult(uri) } returns other.getParseResult(otherUri)

        val (context, text) = assertNotNull(service.createContextWithSource(uri))

        assertSame(snapshot.parseResult.astModel, context.astModel)
        assertSame(snapshot.parseResult.ast, context.moduleNode)
        assertEquals(content, text)
    }
}
