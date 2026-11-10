package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A fatal [Error] escaping a definition request (#1311 lets a StackOverflowError propagate) fails that request
 * with an internal error and leaves the server working, whether it is raised in the request's compile or inside
 * a resolution strategy. The request runs as it does in the server: a
 * `future` in a SupervisorJob scope (GroovyLanguageServer), answered through LSP4J's [RemoteEndpoint].
 */
class DefinitionErrorBoundaryTest {
    private val boom = URI.create("file:///boundary/Boom.groovy")
    private val ok = URI.create("file:///boundary/Ok.groovy")
    private val okSource = "class Ok {\n    def m() {\n        def v = 1\n        v\n    }\n}\n"

    /** The first parse of [boom] overflows, as a recursion bug deep in a request would. */
    private val armed = AtomicBoolean(true)
    private val documents = DocumentProvider()
    private val compilation = GroovyCompilationService(
        documentProvider = documents,
        workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    if (request.uri == boom && armed.compareAndSet(true, false)) {
                        throw StackOverflowError("simulated deep recursion")
                    }
                    return delegate.parse(request)
                }
            }
        },
    )

    /** When armed, the next source lookup overflows inside ClasspathResolutionStrategy, a definition strategy. */
    private val navigatorArmed = AtomicBoolean(false)
    private val navigator = object : SourceNavigator by SourceNavigationService() {
        override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult =
            if (navigatorArmed.compareAndSet(true, false)) {
                throw StackOverflowError("simulated deep recursion in a strategy")
            } else {
                SourceNavigator.SourceResult.BinaryOnly(classpathUri, className, "test")
            }
    }

    /** The server's own request scope: `CoroutineScope(dispatcher + SupervisorJob())` in GroovyLanguageServer. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val service = GroovyTextDocumentService(
        scope,
        compilation,
        GroovyTextDocumentServiceOptions(documentProvider = documents, sourceNavigator = navigator),
    )
    private val responses = LinkedBlockingQueue<Message>()
    private val endpoint = RemoteEndpoint({ responses.put(it) }, ServiceEndpoints.toEndpoint(service))

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun definition(id: String, uri: URI, line: Int, character: Int): ResponseMessage {
        endpoint.consume(
            RequestMessage().apply {
                setId(id)
                method = "textDocument/definition"
                params = DefinitionParams(TextDocumentIdentifier(uri.toString()), Position(line, character))
            },
        )
        val response = assertNotNull(responses.poll(WAIT_SECONDS, TimeUnit.SECONDS), "request $id never answered")
        return response as ResponseMessage
    }

    @Test
    fun `a stack overflow fails only its own definition request`() {
        documents.put(boom, "class Boom {}", 1)
        assertOnlyFirstRequestFails { definition("1", boom, 0, 6) }
    }

    @Test
    fun `a stack overflow inside a definition strategy fails only its own request`() {
        armed.set(false)
        val imports = URI.create("file:///boundary/Imports.groovy")
        documents.put(imports, "import java.util.ArrayList\n\nclass Imports {}\n", 1)
        navigatorArmed.set(true)
        assertOnlyFirstRequestFails { definition("1", imports, 0, IMPORTED_CLASS_COLUMN) }
        assertFalse(navigatorArmed.get(), "the request never reached the overflowing strategy")
    }

    /** [first] must fail with an internal error; a following request is served, and nothing else is hurt. */
    private fun assertOnlyFirstRequestFails(first: () -> ResponseMessage) {
        documents.put(ok, okSource, 1)
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val failed = first()
            assertEquals(ResponseErrorCode.InternalError.value, failed.error?.code, "the overflow must fail it")

            val answered = definition("2", ok, 3, 8)
            assertNull(answered.error, "the next request must still be served")
            @Suppress("UNCHECKED_CAST")
            val locations = (answered.result as Either<List<Location>, List<LocationLink>>).left
            assertTrue(locations.isNotEmpty(), "the next request must still resolve definitions")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue(scope.isActive, "the server's request scope must survive the overflow")
        assertEquals(emptyList(), uncaught, "the overflow must not reach an uncaught-exception handler")
    }

    private companion object {
        const val WAIT_SECONDS = 30L

        /** Inside `ArrayList` in `import java.util.ArrayList`. */
        const val IMPORTED_CLASS_COLUMN = 20
    }
}
