package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two compiles of successive versions of one open buffer can finish out of order. The newest version must
 * own the cache, the symbol index and the semantic document, whichever compile finishes last (#1383).
 */
class BufferVersionOrderTest {
    private val uri = URI.create("file:///Ordered.groovy")
    private val older = "class Older {}\n"
    private val newer = "class Newer {}\n"
    private val olderParsing = CountDownLatch(1)
    private val releaseOlder = CountDownLatch(1)
    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also {
                    if (request.uri == uri && request.content == older) {
                        olderParsing.countDown()
                        check(releaseOlder.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "older compile never released" }
                    }
                }
            }
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterEach
    fun tearDown() {
        releaseOlder.countDown()
        scope.cancel()
    }

    private fun cachedText() =
        service.compilationCacheService.getCachedWithContent(uri, service.workspaceManager.fingerprintFor(uri))?.first

    private fun semanticText() = service.getWorkspaceSymbolIndex()?.getDocumentSnapshot(uri)?.sourceText

    @Test
    fun `the newest buffer version wins although the older compile finishes last`(): Unit = runBlocking {
        service.onDocumentOpened(uri)
        documents.put(uri, older, 1)
        val first = service.compileAsync(scope, uri, older)
        assertTrue(olderParsing.await(WAIT_SECONDS, TimeUnit.SECONDS), "the older compile never started")

        val current = assertNotNull(documents.update(uri, newer, 2))
        service.compileAsync(scope, uri, newer).await()
        releaseOlder.countDown()
        first.await()

        assertEquals(newer, cachedText(), "the cache must hold the newest version")
        assertTrue(service.symbolIndexingService.isIndexed(uri, current), "the symbol index must hold it too")
        assertEquals(newer, semanticText(), "and the semantic document (#1253's ordering)")
    }

    @Test
    fun `a reopened buffer starts a new order, so its first version is accepted`(): Unit = runBlocking {
        releaseOlder.countDown() // nothing waits in this test
        service.onDocumentOpened(uri)
        documents.put(uri, newer, 5)
        service.compileAsync(scope, uri, newer).await()

        documents.remove(uri)
        service.onClosedSourceChanged(uri)
        service.onDocumentOpened(uri)
        documents.put(uri, older, 1) // a new lifecycle restarts the client's version sequence
        service.compileAsync(scope, uri, older).await()

        assertEquals(older, cachedText(), "version 1 of the new lifecycle is not refused by the old version 5")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
