package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.albertocavalcante.groovylsp.GroovyLanguageServer
import com.github.albertocavalcante.groovylsp.services.GroovyLanguageClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}
private const val DEFAULT_PORT = 8080

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

    private val clientExecutor = Executors.newFixedThreadPool(10)

    override fun run() {
        when (mode) {
            "stdio" -> runStdio()
            "socket" -> runSocket(port)
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

    internal fun runSocket(port: Int) {
        try {
            ServerSocket(port).use { serverSocket ->
                logger.info { "Listening on port $port..." }
                addShutdownHook(serverSocket)
                acceptConnections(serverSocket)
            }
        } catch (e: BindException) {
            logger.error(e) { "Failed to bind to port $port (port may be in use)" }
            throw ProgramResult(1)
        } catch (e: IOException) {
            logger.error(e) { "IO error starting server" }
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
            socket.use {
                try {
                    startServer(it.getInputStream(), it.getOutputStream())
                } catch (e: IOException) {
                    logger.error(e) { "Error handling client connection" }
                }
            }
        }
    }

    private fun startServer(input: InputStream, output: OutputStream) {
        val server = GroovyLanguageServer()
        val launcher = Launcher.Builder<GroovyLanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(GroovyLanguageClient::class.java)
            .setInput(input)
            .setOutput(output)
            .create()

        val client = launcher.remoteProxy
        server.connect(client)

        logger.info { "Language Server initialized and listening" }
        launcher.startListening().get()
    }
}
