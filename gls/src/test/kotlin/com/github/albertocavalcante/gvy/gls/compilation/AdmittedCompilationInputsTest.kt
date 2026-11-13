// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class AdmittedCompilationInputsTest {
    @Test
    fun `receipts preserve list domains order duplicates and exact URI spelling`() {
        val first = inputs(listOf(Path.of("one"), Path.of("two")))
        assertNotEquals(first.fingerprint, inputs(listOf(Path.of("two"), Path.of("one"))).fingerprint)
        assertNotEquals(first.fingerprint, inputs(listOf(Path.of("one"), Path.of("two"), Path.of("one"))).fingerprint)
        assertNotEquals(first.fingerprint, inputs(workspace = listOf(Path.of("one"), Path.of("two"))).fingerprint)
        assertNotEquals(
            inputs(open = mapOf(URI.create("file://server/path%41.groovy") to "text")).fingerprint,
            inputs(open = mapOf(URI.create("file://server/pathA.groovy") to "text")).fingerprint,
        )
    }

    @Test
    fun `receipts preserve malformed UTF16 virtual URI identities without replacement collisions`() {
        val first = URI.create("untitled:\uD800")
        val second = URI.create("untitled:\uD801")
        assertNotEquals(first.toString(), second.toString())
        assertNotEquals(
            inputs(open = mapOf(first to "text")).fingerprint,
            inputs(open = mapOf(second to "text")).fingerprint,
        )
    }

    @Test
    fun `request projection copies collections and keys admission rather than source bytes`() {
        val roots = mutableListOf(Path.of("root"))
        val buffers = linkedMapOf(URI.create("untitled:Example") to "before")
        val captured = inputs(roots, open = buffers)
        val key = captured.fingerprint
        roots.clear()
        buffers.clear()
        assertEquals(listOf(Path.of("root")), captured.sourceRoots)
        assertEquals(key, captured.fingerprint)
        assertEquals(
            inputs(open = mapOf(URI.create("untitled:Example") to "before")).fingerprint,
            inputs(open = mapOf(URI.create("untitled:Example") to "after")).fingerprint,
        )
    }

    @Test
    fun `receipt construction preserves cancellation without probing paths`() {
        val cancelled = CancellationException("cancelled")
        val admission = SourceInputAdmission({ error("Fingerprinting must not perform filesystem admission") }) {
            throw cancelled
        }
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                AdmittedCompilationInputs(emptyList(), emptyList(), emptyMap(), admission)
            },
        )
    }

    private fun inputs(
        roots: List<Path> = emptyList(),
        workspace: List<Path> = emptyList(),
        open: Map<URI, String> = emptyMap(),
    ) = AdmittedCompilationInputs(roots, workspace, open, SourceInputAdmission({ false }) {})
}
