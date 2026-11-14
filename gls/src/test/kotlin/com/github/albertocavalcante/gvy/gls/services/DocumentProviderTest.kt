// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentProviderTest {
    @Test
    fun `analysis session belongs to one document incarnation`() {
        val documents = DocumentProvider()
        val uri = URI.create("file:///session.groovy")
        documents.put(uri, "old", 1, "old-session")
        val old = documents.getBuffer(uri)!!
        documents.update(uri, "edited", 2)
        assertEquals("old-session", documents.getBuffer(uri)!!.incarnation.analysisSessionId)
        documents.put(uri, "new", 1, "new-session")
        assertEquals("old-session", old.incarnation.analysisSessionId)
        assertEquals("new-session", documents.getBuffer(uri)!!.incarnation.analysisSessionId)
    }

    private val uri = URI.create("file:///workspace/Example.groovy")

    @Test
    fun `captured snapshot retains matching content and version after replacement and close`() {
        val documents = DocumentProvider()
        documents.put(uri, "opened", 1)
        val captured = documents.getSnapshot(uri)

        documents.update(uri, "edited", 5)
        documents.remove(uri)

        assertEquals(DocumentSnapshot("opened", 1), captured)
        assertNull(documents.getSnapshot(uri))
        assertEquals(emptyMap(), documents.snapshot())
        assertEquals(emptySet(), documents.getAllUris())
    }

    @Test
    fun `unversioned API and text snapshot remain compatible`() {
        val documents = DocumentProvider()
        documents.put(uri, "internal content")

        assertEquals("internal content", documents.get(uri))
        assertEquals(DocumentSnapshot("internal content", null), documents.getSnapshot(uri))
        assertEquals(mapOf(uri to "internal content"), documents.snapshot())
        assertEquals(setOf(uri), documents.getAllUris())
        assertEquals(DocumentSnapshot("client content", -3), documents.update(uri, "client content", -3))
    }

    @Test
    fun `closed buffer rejects changes and reopened buffer can restart version sequence`() {
        val documents = DocumentProvider()
        documents.put(uri, "old lifecycle", 100)
        documents.remove(uri)
        assertNull(documents.update(uri, "late change", 101))
        assertNull(documents.getSnapshot(uri))

        documents.put(uri, "reopened", 1)

        assertEquals(DocumentSnapshot("edited", 2), documents.update(uri, "edited", 2))
    }

    @Test
    fun `version comparison accepts gaps and signed boundaries without overflow`() {
        val documents = DocumentProvider()
        documents.put(uri, "initial", Int.MIN_VALUE)

        assertEquals(DocumentSnapshot("latest", Int.MAX_VALUE), documents.update(uri, "latest", Int.MAX_VALUE))
        assertNull(documents.update(uri, "obsolete", Int.MIN_VALUE))
        assertNull(documents.update(uri, "duplicate", Int.MAX_VALUE))
        assertEquals(DocumentSnapshot("latest", Int.MAX_VALUE), documents.getSnapshot(uri))
    }

    @Test
    fun `concurrent changes cannot regress version or separate content from its version`() {
        val documents = DocumentProvider()
        documents.put(uri, "v0", 0)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..100).reversed().map { version ->
                Callable {
                    documents.update(uri, "v$version", version)?.let { accepted ->
                        assertEquals("v${accepted.version}", accepted.content)
                    }
                    documents.getSnapshot(uri)?.let { observed ->
                        assertEquals("v${observed.version}", observed.content)
                    }
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }

            assertEquals(DocumentSnapshot("v100", 100), documents.getSnapshot(uri))
        } finally {
            executor.shutdownNow()
        }
    }
}
