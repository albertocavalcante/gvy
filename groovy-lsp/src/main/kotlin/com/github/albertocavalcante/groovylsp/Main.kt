package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.launch.LSPLauncher
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.ServerSocket
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("GroovyLSP")
private const val DEFAULT_PORT = 8080

fun main(args: Array<String>) {
    // If no args or starts with stdio/socket directly (backward compatibility)
    if (args.isEmpty()) {
        runServe("stdio", emptyList())
        return
    }

    val command = args[0]
    val commandArgs = args.drop(1)

    try {
        when (command) {
            "serve" -> {
                // Parse serve-specific flags if any? For now just handle mode.
                // Usage: serve [mode] [port]
                val mode = commandArgs.firstOrNull() ?: "stdio"
                runServe(mode, commandArgs.drop(1))
            }
            "execute" -> runExecute(commandArgs)
            "check" -> runCheck(commandArgs)
            "version" -> runVersion()
            "--help", "-h", "help" -> {
                printHelp()
                exitProcess(0)
            }
            // Backward compatibility
            "stdio" -> runServe("stdio", commandArgs)
            "socket" -> runServe("socket", commandArgs)
            else -> {
                if (command.startsWith("-")) {
                    printHelp()
                    exitProcess(1)
                }
                logger.error("Unknown command: $command")
                printHelp()
                exitProcess(1)
            }
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.error("Error executing command", e)
        exitProcess(1)
    }
}

private fun runServe(mode: String, args: List<String>) {
    when (mode) {
        "stdio" -> runStdio()
        "socket" -> {
            val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
            runSocket(port)
        }
        else -> {
            logger.error("Unknown serve mode: $mode")
            println("Usage: groovy-lsp serve [stdio|socket] [options]")
            exitProcess(1)
        }
    }
}

private fun runExecute(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: groovy-lsp execute <command> [arguments]")
        println("Example: groovy-lsp execute groovy.version")
        exitProcess(1)
    }

    val commandName = args[0]
    // Pass remaining args as list of objects/strings
    val commandArgs = args.drop(1).map { it as Any }

    val server = GroovyLanguageServer()
    val params = ExecuteCommandParams(commandName, commandArgs)

    try {
        // We might need to initialize the server partially if the command depends on it
        // But for simple commands like 'version' we don't.
        // For workspace commands, we'd need to mock initialization.

        val future = server.workspaceService.executeCommand(params)
        val result = future.get()
        if (result != null) {
            println(result)
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.error("Error executing command '$commandName'", e)
        exitProcess(1)
    }
}

private fun runCheck(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: groovy-lsp check <file> [file...]")
        exitProcess(1)
    }

    val server = GroovyLanguageServer()
    // Cast safely or assume it's our implementation
    val service = server.getTextDocumentService() as? GroovyTextDocumentService

    if (service == null) {
        logger.error("Failed to retrieve GroovyTextDocumentService")
        exitProcess(1)
    }

    runBlocking {
        for (arg in args) {
            try {
                val file = File(arg)
                if (!file.exists()) {
                    System.err.println("File not found: $arg")
                    continue
                }

                val uri = file.toURI()
                val content = file.readText()
                val diagnostics = service.diagnose(uri, content)

                for (d in diagnostics) {
                    val severity = d.severity?.toString()?.uppercase() ?: "UNKNOWN"
                    println(
                        "${file.path}:${d.range.start.line + 1}:${d.range.start.character + 1}: [$severity] ${d.message}",
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("Error checking file $arg", e)
            }
        }
    }
}

private fun runVersion() {
    println("groovy-lsp version ${Version.current}")
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
    try {
        ServerSocket(port).use { serverSocket ->
            logger.info("Listening on port $port...")
            addShutdownHook(serverSocket)
            acceptConnections(serverSocket)
        }
    } catch (e: BindException) {
        logger.error("Failed to bind to port $port (port may be in use)", e)
        exitProcess(1)
    } catch (e: IOException) {
        logger.error("IO error starting server", e)
        exitProcess(1)
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

        Usage: groovy-lsp [command] [options]

        Commands:
          serve [mode]       Run the language server
                             Modes: stdio (default), socket [port]
          execute <cmd>      Execute a custom LSP command
          check <file>...    Run diagnostics on specified files
          version            Print the version
          help               Show this help message

        Examples:
          groovy-lsp serve stdio             # Run in stdio mode
          groovy-lsp serve socket 9000       # Run in socket mode on port 9000
          groovy-lsp execute groovy.version  # Execute 'groovy.version' command
          groovy-lsp check MyFile.groovy     # Check MyFile.groovy for errors
          groovy-lsp version                 # Show version
        """.trimIndent(),
    )
}
