// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import com.github.albertocavalcante.gvy.gls.transport.lsp4j.registerStatusNotificationAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}
private const val DEFAULT_PORT = 8080
private const val ACCEPT_BACKLOG = 50

/**
 * Binds the socket-mode listener. Defaults to the loopback interface so the server (which can read
 * workspace files and run build tooling) is not exposed to the network unless explicitly requested.
 *
 * "Loopback" here is the JVM's loopback address, normally IPv4 `127.0.0.1`. A single listener cannot serve
 * both families, so IPv6-only clients must be given an explicit `--host ::1`.
 *
 * @throws java.net.UnknownHostException if [host] cannot be resolved
 */
internal fun bindServerSocket(port: Int, host: String? = null): ServerSocket {
    val address = if (host == null) InetAddress.getLoopbackAddress() else InetAddress.getByName(host)
    if (!address.isLoopbackAddress) {
        logger.warn {
            "Binding to non-loopback address $address exposes the unauthenticated language server to the network"
        }
    }
    return ServerSocket(port, ACCEPT_BACKLOG, address)
}

/**
 * Runs the Groovy Language Server in either stdio or socket mode.
 */
class LspCommand : CliktCommand(name = "lsp") {
    override fun help(context: Context) = "Run the language server"

    private val mode by argument()
        .default("stdio")

    private val port by option("-p", "--port")
        .int()
        .default(DEFAULT_PORT)

    private val host by option("--host", help = "Address to bind in socket mode (default: loopback only)")

    private val clientExecutor = Executors.newFixedThreadPool(10)

    init {
        // Ensure executor is shut down gracefully on application exit
        Runtime.getRuntime().addShutdownHook(
            Thread {
                try {
                    logger.info { "Shutting down client executor" }
                    clientExecutor.shutdown()
                    if (!clientExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        logger.warn { "Executor did not terminate in time, forcing shutdown" }
                        clientExecutor.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    logger.error(e) { "Interrupted while shutting down executor" }
                    clientExecutor.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            },
        )
    }

    override fun run() {
        when (mode) {
            "stdio" -> runStdio()
            "socket" -> runSocket(port, host)
            else -> {
                echo("Unknown mode: $mode. Use 'stdio' or 'socket'.", err = true)
                throw ProgramResult(1)
            }
        }
    }

    /**
     * Runs the LSP server in stdio mode.
     * Made internal so GlsCommand can call it directly without going through Clikt parsing.
     */
    internal fun runStdio() {
        val input = System.`in`
        val output = System.out

        // Redirect System.out to System.err IMMEDIATELY to prevent pollution of LSP messages
        System.setOut(System.err)

        logger.info { "Starting Groovy Language Server in stdio mode" }
        startServer(input, output)
    }

    internal fun runSocket(port: Int, host: String? = null) {
        try {
            bindServerSocket(port, host).use { serverSocket ->
                logger.info { "Listening on ${serverSocket.inetAddress}:${serverSocket.localPort}..." }
                addShutdownHook(serverSocket)
                acceptConnections(serverSocket)
            }
        } catch (e: IOException) {
            logger.error(e) {
                when (e) {
                    is UnknownHostException -> "Invalid --host '$host': cannot resolve address"
                    is BindException -> "Failed to bind to port $port (port may be in use)"
                    else -> "IO error starting server"
                }
            }
            throw ProgramResult(1)
        }
    }

    private fun addShutdownHook(serverSocket: ServerSocket) {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                try {
                    serverSocket.close()
                    logger.info { "Server socket closed" }
                } catch (e: IOException) {
                    logger.error(e) { "Error closing server socket" }
                }
            },
        )
    }

    private fun acceptConnections(serverSocket: ServerSocket) {
        while (!serverSocket.isClosed) {
            try {
                val socket = serverSocket.accept()
                logger.info { "Client connected from ${socket.inetAddress}" }
                handleClientConnection(socket)
            } catch (e: IOException) {
                if (!serverSocket.isClosed) {
                    logger.error(e) { "Error accepting connection" }
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        clientExecutor.submit {
            val originalName = Thread.currentThread().name
            Thread.currentThread().name = "gls-client-${socket.inetAddress}"
            try {
                socket.use {
                    try {
                        startServer(it.getInputStream(), it.getOutputStream(), exitJvmOnExit = false)
                    } catch (e: IOException) {
                        logger.error(e) { "Error handling client connection" }
                    }
                }
            } finally {
                Thread.currentThread().name = originalName
            }
        }
    }

    private fun startServer(input: InputStream, output: OutputStream, exitJvmOnExit: Boolean = true) {
        // In socket mode one JVM serves many clients, so a client's `exit` must not end the process.
        val server = if (exitJvmOnExit) GroovyLanguageServer() else GroovyLanguageServer(exitProcess = {})
        val launcher = Launcher.Builder<GroovyLanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(GroovyLanguageClient::class.java)
            .setInput(input)
            .setOutput(output)
            .configureGson(::registerStatusNotificationAdapter)
            .create()

        val client = launcher.remoteProxy
        server.connect(client)

        logger.info { "Language Server initialized and listening" }
        launcher.startListening().get()
    }
}
