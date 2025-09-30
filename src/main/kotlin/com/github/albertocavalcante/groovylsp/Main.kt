package com.github.albertocavalcante.groovylsp

import org.eclipse.lsp4j.launch.LSPLauncher
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.ServerSocket
import kotlin.system.exitProcess

private const val DEFAULT_PORT = 8080

// Use lazy initialization to avoid class loading issues at startup
private val logger by lazy { LoggerFactory.getLogger("GroovyLSP") }

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "stdio"

    try {
        when (mode) {
            "stdio" -> runStdio()
            "socket" -> {
                val port = args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
                runSocket(port)
            }
            "--help", "-h" -> {
                printHelp()
                exitProcess(0)
            }
            else -> {
                logger.error("Unknown mode: $mode")
                printHelp()
                exitProcess(1)
            }
        }
    } catch (e: IOException) {
        logger.error("IO error starting Groovy Language Server", e)
        exitProcess(1)
    } catch (e: BindException) {
        logger.error("Failed to bind to port (port may be in use)", e)
        exitProcess(1)
    } catch (e: IllegalArgumentException) {
        logger.error("Invalid arguments provided", e)
        exitProcess(1)
    } catch (e: NumberFormatException) {
        logger.error("Invalid port number provided", e)
        exitProcess(1)
    }
}

private fun runStdio() {
    val input = System.`in`
    val output = System.out

    // Redirect System.out to System.err IMMEDIATELY to prevent pollution of LSP messages
    System.setOut(System.err)

    // Now safe to log (will go to stderr)
    logger.info("Starting Groovy Language Server in stdio mode")

    startServer(input, output)
}

private fun runSocket(port: Int) {
    logger.info("Starting Groovy Language Server on port $port")

    ServerSocket(port).use { serverSocket ->
        logger.info("Listening on port $port...")
        addShutdownHook(serverSocket)
        acceptConnections(serverSocket)
    }
}

private fun addShutdownHook(serverSocket: ServerSocket) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            try {
                serverSocket.close()
                logger.info("Server socket closed")
            } catch (e: IOException) {
                logger.error("Error closing server socket", e)
            }
        },
    )
}

private fun acceptConnections(serverSocket: ServerSocket) {
    while (!serverSocket.isClosed) {
        try {
            val socket = serverSocket.accept()
            logger.info("Client connected from ${socket.inetAddress}")
            handleClientConnection(socket)
        } catch (e: IOException) {
            if (!serverSocket.isClosed) {
                logger.error("Error accepting connection", e)
            }
        }
    }
}

private fun handleClientConnection(socket: java.net.Socket) {
    Thread({
        socket.use {
            try {
                startServer(it.getInputStream(), it.getOutputStream())
            } catch (e: IOException) {
                logger.error("Error handling client connection", e)
            }
        }
    }, "groovy-lsp-client-${socket.inetAddress}").start()
}

private fun startServer(input: InputStream, output: OutputStream) {
    val server = GroovyLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, input, output)

    val client = launcher.remoteProxy
    server.connect(client)

    logger.info("Language Server initialized and listening")
    launcher.startListening().get()
}

private fun printHelp() {
    println(
        """
        Groovy Language Server

        Usage: groovy-lsp [mode] [options]

        Modes:
          stdio              Run in stdio mode (default)
          socket [port]      Run in socket mode on specified port (default: 8080)

        Options:
          --help, -h         Show this help message

        Examples:
          groovy-lsp                    # Start in stdio mode
          groovy-lsp stdio              # Start in stdio mode
          groovy-lsp socket             # Start in socket mode on port 8080
          groovy-lsp socket 9000        # Start in socket mode on port 9000
        """.trimIndent(),
    )
}
