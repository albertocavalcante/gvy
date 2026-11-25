// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentIncarnation
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class SnapshotOwnershipTest {
    private val uri = URI.create("file:///Snapshot.groovy")
    private val source = "class Snapshot {}"

    @Test
    fun `a compile retains the exact parser result with its source`(): Unit = runBlocking {
        val parsed = AtomicReference<ParseResult>()
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also(parsed::set)
            }
        })
        try {
            service.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
            val result = assertIs<SnapshotAcquisition.Ready>(service.acquireSnapshot(uri))
            assertSame(parsed.get(), result.snapshot.parseResult)
            assertEquals(source, result.snapshot.sourceText)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `a later compile at a higher phase cannot reuse a lower phase cache entry`(): Unit = runBlocking {
        val phases = mutableListOf<Int>()
        val service = GroovyCompilationService(workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    phases.add(request.compilePhase)
                    return delegate.parse(request)
                }
            }
        })
        try {
            service.compile(uri, source, Phases.CONVERSION)
            service.compile(uri, source, Phases.CANONICALIZATION)
            assertEquals(listOf(Phases.CONVERSION, Phases.CANONICALIZATION), phases)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `identical document state in different lifecycles cannot share work`() {
        val snapshot = DocumentSnapshot(source, null)
        val first = CompilationIdentity(source, "fingerprint", snapshot, DocumentIncarnation())
        val reopened = CompilationIdentity(source, "fingerprint", snapshot, DocumentIncarnation())

        assertFalse(first.matches(reopened))
    }
}
