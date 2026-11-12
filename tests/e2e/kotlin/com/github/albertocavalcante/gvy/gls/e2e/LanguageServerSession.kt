package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.testing.api.GroovyLanguageServerProtocol
import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.services.LanguageServer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** An LSP connection and the process, streams and executor exclusively owned by that connection. */
class LanguageServerSession internal constructor(
    val process: Process?,
    val server: LanguageServer,
    val endpoint: RemoteEndpoint,
    val client: HarnessLanguageClient,
    private val resources: SessionResources = SessionResources(),
    private val exitCode: CompletableFuture<Int>? = null,
    private val typedServerProxy: GroovyLanguageServerProtocol? = null,
    private val wireCapture: BoundedWireCapture? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    val groovyServer: GroovyLanguageServerProtocol
        get() = checkNotNull(typedServerProxy) {
            "Typed server proxy not available; use Launcher.Builder with GroovyLanguageServerProtocol"
        }

    /** A bounded immutable observation, independent of SDK decoding. Null means capture was not installed. */
    fun snapshotWireEvidence(): WireEvidenceSnapshot? = wireCapture?.snapshot()

    /** Sends exit and validates its observed outcome, independently of unconditional teardown. */
    fun exit(expectedCode: Int = 0, timeout: Duration = EXIT_TIMEOUT) {
        require(!timeout.isNegative && !timeout.isZero) { "Exit timeout must be positive" }
        val inProcessExit = if (process == null) checkNotNull(exitCode) { "No in-process exit observer" } else null
        server.exit()
        val actualCode = if (process != null) {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw TimeoutException("Language server did not exit within $timeout")
            }
            process.exitValue()
        } else {
            checkNotNull(inProcessExit).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }
        if (actualCode != expectedCode) {
            throw AssertionError("Language server exited with code $actualCode (expected $expectedCode)")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resources.close()
    }

    private companion object {
        val EXIT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
