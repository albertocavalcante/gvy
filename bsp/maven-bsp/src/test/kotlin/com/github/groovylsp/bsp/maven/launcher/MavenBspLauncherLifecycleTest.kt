// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.groovylsp.bsp.maven.launcher

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `serve` must return promptly after `build/exit` even when the client keeps its end of the stream open,
 * because a real client does not close stdin on exit.
 */
class MavenBspLauncherLifecycleTest {

    @TempDir
    lateinit var tempDir: Path

    private class Session(
        val toServer: PipedOutputStream,
        val output: ByteArrayOutputStream,
        val result: CompletableFuture<Int>,
    ) {
        fun send(json: String) {
            val body = json.toByteArray(Charsets.UTF_8)
            toServer.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            toServer.write(body)
            toServer.flush()
        }
    }

    /** Starts `serve` on a worker thread; the client side of the pipe is left open on purpose. */
    private fun start(): Session {
        val toServer = PipedOutputStream()
        val input = PipedInputStream(toServer, PIPE_SIZE)
        val server = MavenBspLauncher.createServer(tempDir)
        val output = ByteArrayOutputStream()
        val result = CompletableFuture.supplyAsync {
            MavenBspLauncher.serve(server, input, output)
        }
        return Session(toServer, output, result)
    }

    @Test
    @Timeout(30)
    fun `exits with zero after shutdown and exit while stdin stays open`() {
        val session = start()

        session.send("""{"jsonrpc":"2.0","id":1,"method":"build/shutdown"}""")
        session.send("""{"jsonrpc":"2.0","method":"build/exit"}""")

        assertThat(session.result.get(EXIT_DEADLINE_SECONDS, TimeUnit.SECONDS)).isEqualTo(0)
        // The shutdown response is written before the exit notification is handled.
        assertThat(session.output.toString(Charsets.UTF_8)).contains("\"id\":1").contains("\"result\"")
    }

    @Test
    @Timeout(30)
    fun `exits with one when exit arrives before shutdown`() {
        val session = start()

        session.send("""{"jsonrpc":"2.0","method":"build/exit"}""")

        assertThat(session.result.get(EXIT_DEADLINE_SECONDS, TimeUnit.SECONDS)).isEqualTo(1)
    }

    @Test
    @Timeout(30)
    fun `exits with one when the client closes the stream without exit`() {
        val session = start()

        session.toServer.close()

        assertThat(session.result.get(EXIT_DEADLINE_SECONDS, TimeUnit.SECONDS)).isEqualTo(1)
    }

    private companion object {
        const val PIPE_SIZE = 64 * 1024
        const val EXIT_DEADLINE_SECONDS = 10L
    }
}
