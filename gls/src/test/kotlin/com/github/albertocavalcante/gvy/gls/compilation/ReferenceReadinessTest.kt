// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReferenceReadinessTest {
    private val uri = URI.create("file:///ReferenceReadiness.groovy")
    private val source = "class ReferenceReadiness { def target() {} }"
    private val documents = DocumentProvider().apply { put(uri, source) }
    private val compilation = GroovyCompilationService(documentProvider = documents)

    @AfterEach
    fun shutdown() = compilation.shutdown()

    @Test
    fun `missing or older semantic source cannot answer even an empty query`(): Unit = runBlocking {
        assertUnavailable()
        compilation.compile(uri, source)
        val current = compilation.referenceReadiness(uri).query { it.index.getDocumentSnapshot(uri)?.sourceText }
        assertEquals(source, assertIs<SnapshotQuery.Answer<*>>(current).value)

        documents.put(uri, "class ReferenceReadiness {}")
        assertUnavailable()
    }

    @Test
    fun `semantic entries kept after an input retirement need a current publication`(): Unit = runBlocking {
        compilation.compile(uri, source)
        val phase = assertNotNull(compilation.semanticPhaseOf(uri))
        compilation.clearCompileCaches()
        assertEquals(phase, compilation.semanticPhaseOf(uri))
        assertUnavailable()
        compilation.compile(uri, source)
        val current = compilation.referenceReadiness(uri).query { it.index.getDocumentSnapshot(uri)?.sourceText }
        assertEquals(source, assertIs<SnapshotQuery.Answer<*>>(current).value)
    }

    @Test
    fun `equal text reopen during a query retires its answer`(): Unit = runBlocking {
        compilation.compile(uri, source)
        val current = compilation.referenceReadiness(uri).query {
            documents.remove(uri)
            documents.put(uri, source)
            emptyList<String>()
        }
        assertSame(SnapshotQuery.Stale, current)
    }

    @Test
    fun `index mutation while computing the last reference retires the result`(): Unit = runBlocking {
        compilation.compile(uri, source)
        val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
        val current = compilation.referenceReadiness(uri).query { view ->
            val original = assertNotNull(view.index.getDocumentSnapshot(uri))
            index.updateDocument(uri, original.document, original.sourceText)
            original.document.occurrences
        }
        assertSame(SnapshotQuery.Stale, current)
    }

    @Test
    fun `adding and removing an unsaved companion retires the original inventory`(): Unit = runBlocking {
        compilation.compile(uri, source)
        val result = compilation.referenceReadiness(uri).query {
            val temporary = URI.create("untitled:Temporary.groovy")
            documents.put(temporary, "class Temporary {}")
            documents.remove(temporary)
            emptyList<String>()
        }
        assertSame(SnapshotQuery.Stale, result)
    }

    @Test
    fun `a rejected buffer change and absent removal preserve the captured inventory`(): Unit = runBlocking {
        val version = DocumentVersion.of(1)
        documents.put(uri, source, version)
        compilation.compile(uri, source)
        val before = documents.captureDocuments()
        val result = compilation.referenceReadiness(uri).query {
            assertNull(documents.update(uri, "ignored", version))
            documents.remove(URI.create("untitled:Absent.groovy"))
            documents.captureDocuments()
        }
        assertSame(before, assertIs<SnapshotQuery.Answer<*>>(result).value)
    }

    private suspend fun assertUnavailable() {
        val result = compilation.referenceReadiness(uri).query { error("Unqualified input reached the query") }
        val failure = assertIs<SnapshotQuery.Failure>(result)
        val response = assertIs<ResponseErrorException>(failure.cause).responseError
        assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
    }
}
