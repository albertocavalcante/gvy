// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.build.maven.AetherSessionFactory
import com.github.albertocavalcante.gvy.build.maven.RepositoryProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.aether.repository.RemoteRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceHttpCancellationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `deadline disconnects endless chunked response and releases cache lock`() {
        assertCancelledTransfer(stall = false, payloadBytes = 1, deadlineMillis = 1_000)
    }

    @Test
    fun `deadline disconnects stalled response and releases cache lock`() {
        assertCancelledTransfer(stall = true, payloadBytes = 1, deadlineMillis = 1_000)
    }

    @Test
    fun `budget disconnects endless oversized checksum before cleanup can drain it`() {
        assertCancelledTransfer(stall = false, payloadBytes = 2_048, deadlineMillis = null)
    }

    @Test
    fun `cancelling one verified lookup leaves a concurrent HTTP operation connected`() {
        AetherSessionFactory.repositorySystem
        StreamingRepository(stall = false, payloadBytes = 1).use { firstServer ->
            StreamingRepository(stall = false, payloadBytes = 1).use { secondServer ->
                val workers = Executors.newFixedThreadPool(2)
                val cancelFirst = CompletableDeferred<Unit>()
                val cancelSecond = CompletableDeferred<Unit>()
                try {
                    val first = workers.submit {
                        runBlocking {
                            val operation = launch {
                                MavenSourceArtifactResolver(directory.resolve("first"), firstServer)
                                    .resolveVerifiedSourceArtifact(request())
                            }
                            cancelFirst.await()
                            operation.cancelAndJoin()
                        }
                    }
                    val second = workers.submit {
                        runBlocking {
                            val operation = launch {
                                MavenSourceArtifactResolver(directory.resolve("second"), secondServer)
                                    .resolveVerifiedSourceArtifact(request(module = "other"))
                            }
                            cancelSecond.await()
                            operation.cancelAndJoin()
                        }
                    }
                    // Maven serializes equal coordinates across sessions; use distinct artifacts for parallel sockets.
                    assertTrue(firstServer.firstPayload.await(5, TimeUnit.SECONDS))
                    assertTrue(secondServer.firstPayload.await(5, TimeUnit.SECONDS))
                    cancelFirst.complete(Unit)
                    first.get(2, TimeUnit.SECONDS)
                    assertTrue(firstServer.disconnected.await(2, TimeUnit.SECONDS))
                    assertEquals(1L, secondServer.disconnected.count)
                    assertTrue(secondServer.awaitAnotherChunk(), "Other HTTP connection stopped transferring")
                    cancelSecond.complete(Unit)
                    second.get(2, TimeUnit.SECONDS)
                    assertTrue(secondServer.disconnected.await(2, TimeUnit.SECONDS))
                    firstServer.assertHealthy()
                    secondServer.assertHealthy()
                } finally {
                    cancelFirst.complete(Unit)
                    cancelSecond.complete(Unit)
                    firstServer.close()
                    secondServer.close()
                    workers.shutdownNow()
                    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun assertCancelledTransfer(stall: Boolean, payloadBytes: Int, deadlineMillis: Long?) {
        // Initialize the shared resolver before timing the HTTP operation.
        AetherSessionFactory.repositorySystem
        StreamingRepository(stall, payloadBytes).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val resolver = MavenSourceArtifactResolver(directory, server)
                val result = executor.submit<VerifiedSourceArtifactResult?> {
                    runBlocking {
                        if (deadlineMillis == null) {
                            resolver.resolveVerifiedSourceArtifact(request())
                        } else {
                            withTimeoutOrNull(deadlineMillis) { resolver.resolveVerifiedSourceArtifact(request()) }
                        }
                    }
                }
                assertTrue(server.firstPayload.await(5, TimeUnit.SECONDS), "HTTP payload was never sent")
                assertEquals(
                    if (deadlineMillis == null) VerifiedSourceArtifactResult.LimitExceeded else null,
                    result.get(2, TimeUnit.SECONDS),
                )
                assertTrue(server.disconnected.await(2, TimeUnit.SECONDS), "Client kept the HTTP connection open")
                assertEquals(1L, server.release.count, "Server must remain open through cancellation assertions")
                val lock = executor.submit<Boolean> {
                    withSourceCacheLock(defaultVerifiedSourceCacheRoot(directory)) { true }
                }
                assertTrue(lock.get(1, TimeUnit.SECONDS), "Cancelled request retained the cache lock")
                if (!stall && deadlineMillis != null) assertTrue(server.chunks.get() > 1)
                server.assertHealthy()
            } finally {
                server.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Resolver worker did not terminate")
            }
        }
    }

    private fun request(module: String = "library") = VerifiedSourceArtifactRequest(
        ResolvedArtifactCoordinates("sample", module, "1.0"),
        "a".repeat(64),
        true,
        "cancellation",
    )
}

/** A response with no terminating chunk until test cleanup; the peer reader observes client shutdown. */
private class StreamingRepository(private val stall: Boolean, private val payloadBytes: Int) :
    RepositoryProvider,
    AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(2)
    private val client = AtomicReference<Socket?>()
    private val nextPayload = AtomicReference<CountDownLatch?>()
    val chunks = AtomicInteger()
    val firstPayload = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val failures = ConcurrentLinkedQueue<Throwable>()

    init {
        workers.submit {
            try {
                val socket = server.accept()
                client.set(socket)
                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                while (!input.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                workers.submit {
                    try {
                        assertEquals(-1, input.read(), "Unexpected second request")
                        disconnected.countDown()
                    } catch (failure: SocketException) {
                        failures.add(failure)
                        // A reset also demonstrates that the client abandoned this connection.
                        disconnected.countDown()
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    }
                }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                val chunk = "${payloadBytes.toString(16)}\r\n${"a".repeat(payloadBytes)}\r\n".toByteArray()
                do {
                    output.write(chunk)
                    output.flush()
                    chunks.incrementAndGet()
                    nextPayload.getAndSet(null)?.countDown()
                    firstPayload.countDown()
                    if (stall) release.await()
                } while (!release.await(25, TimeUnit.MILLISECONDS))
            } catch (failure: IOException) {
                if (release.count != 0L) failures.add(failure)
            } catch (failure: InterruptedException) {
                if (release.count != 0L) failures.add(failure)
            } catch (failure: Throwable) {
                failures.add(failure)
            }
        }
    }

    fun assertHealthy() {
        assertTrue(failures.all { it is SocketException }, "Unexpected server errors: $failures")
    }

    fun awaitAnotherChunk(): Boolean {
        val next = CountDownLatch(1)
        nextPayload.set(next)
        return next.await(1, TimeUnit.SECONDS)
    }

    override fun getRepositories(): List<RemoteRepository> = listOf(
        RemoteRepository.Builder("streaming", "default", "http://127.0.0.1:${server.localPort}/").build(),
    )

    override fun close() {
        release.countDown()
        client.get()?.close()
        server.close()
        workers.shutdownNow()
        check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "HTTP test workers did not terminate" }
    }
}
