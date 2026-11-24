// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.errors.ReferenceScanFailedException
import com.github.albertocavalcante.gvy.gls.errors.ReferenceScanPhase
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** A reference scan that fails in either phase fails the flow; it never completes with what it had found. */
class ReferenceProviderFailureTest {
    private val uri = URI.create("file:///Failing.groovy")
    private val text = "class Failing {\n    void helper() {}\n    void run() { helper() }\n}\n"
    private val callOfHelper = Position(2, 19)
    private val compilation = GroovyCompilationService().also { runBlocking { it.compile(uri, text) } }

    @Test
    fun `a failure scanning the document fails the flow`(): Unit = runBlocking {
        val cause = IllegalStateException("document scan broke")
        val visitor = assertNotNull(compilation.getAstModel(uri))
        mockkObject(visitor)
        try {
            every { visitor.getAllNodes() } throws cause

            val failure = assertFailsWith<ReferenceScanFailedException> {
                ReferenceProvider(compilation)
                    .provideReferences(uri.toString(), callOfHelper, includeDeclaration = true)
                    .toList()
            }
            assertEquals(ReferenceScanPhase.DOCUMENT, failure.phase)
            assertSame(cause, failure.cause)
        } finally {
            unmockkObject(visitor)
            compilation.clearCaches()
        }
    }

    @Test
    fun `a failure reading the workspace index fails the flow`(): Unit = runBlocking {
        val cause = IllegalStateException("index unreadable")
        val index = mockk<WorkspaceSymbolIndex>()
        every { index.getDocumentSnapshot(any()) } throws cause

        val failure = assertFailsWith<ReferenceScanFailedException> {
            ReferenceProvider(compilation, index)
                .provideReferences(uri.toString(), callOfHelper, includeDeclaration = true)
                .toList()
        }
        assertEquals(ReferenceScanPhase.WORKSPACE, failure.phase)
        assertSame(cause, failure.cause)
        compilation.clearCaches()
    }
}
