package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovyformatter.OpenRewriteFormatter
import com.github.albertocavalcante.groovylsp.services.GroovyLanguageClient
import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.BindException
import java.net.ServerSocket
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("gls")
private const val DEFAULT_PORT = 8080

// TODO(#340): Migrate to Clikt for CLI handling with ANSI color support.
//   See: https://github.com/albertocavalcante/groovy-lsp/issues/340

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
            "lsp", "serve" -> {
                // Parse lsp-specific flags if any? For now just handle mode.
                // Usage: lsp [mode] [port]  ("serve" kept for backward compatibility)
                val mode = commandArgs.firstOrNull() ?: "stdio"
                runServe(mode, commandArgs.drop(1))
            }

            "execute" -> runExecute(commandArgs)
            "format" -> runFormat(commandArgs)
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
            logger.error("Unknown lsp mode: $mode")
            println("Usage: gls lsp [stdio|socket] [options]")
            exitProcess(1)
        }
    }
}

fun runExecute(args: List<String>, out: PrintStream = System.out) {
    if (args.isEmpty()) {
        out.println("Usage: gls execute <command> [arguments]")
        out.println("Example: gls execute groovy.version")
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
            out.println(result)
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        logger.error("Error executing command '$commandName'", e)
        exitProcess(1)
    } finally {
        server.shutdown().get()
    }
}

fun runCheck(args: List<String>, out: PrintStream = System.out) {
    if (args.isEmpty()) {
        out.println("Usage: gls check [--workspace <dir>] <file> [file...]")
        exitProcess(1)
    }

    var workspace: File? = null
    val filesToCheck = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg == "--workspace") {
            if (i + 1 < args.size) {
                workspace = File(args[i + 1])
                i += 2
            } else {
                out.println("Error: --workspace requires an argument")
                exitProcess(1)
            }
        } else {
            filesToCheck.add(arg)
            i++
        }
    }

    if (filesToCheck.isEmpty()) {
        out.println("Error: No files specified to check")
        exitProcess(1)
    }

    val server = GroovyLanguageServer()
    try {
        if (workspace != null) {
            if (!workspace.exists() || !workspace.isDirectory) {
                out.println("Error: Workspace directory not found: $workspace")
                exitProcess(1)
            }

            // Initialize server with workspace
            val params = org.eclipse.lsp4j.InitializeParams().apply {
                rootUri = workspace.toURI().toString()
                capabilities = org.eclipse.lsp4j.ClientCapabilities()
            }

            // Wait for initialize
            server.initialize(params).get()

            // Trigger initialized (starts dependency resolution)
            server.initialized(org.eclipse.lsp4j.InitializedParams())

            out.println("Resolving dependencies for ${workspace.absolutePath}...")
            if (server.waitForDependencies()) {
                out.println("Dependencies resolved successfully.")
            } else {
                out.println("Warning: Dependency resolution failed or timed out. Checking with limited context.")
            }
        }

        // Cast safely or assume it's our implementation
        val service = server.getTextDocumentService() as? GroovyTextDocumentService

        if (service == null) {
            logger.error("Failed to retrieve GroovyTextDocumentService")
            exitProcess(1)
        }

        runBlocking {
            for (arg in filesToCheck) {
                try {
                    val file = File(arg)
                    if (!file.exists()) {
                        System.err.println("File not found: $arg")
                        continue
                    }

                    val uri = file.toURI()
                    val content = file.readText()
                    // If workspace is set, ensure we use the same URI normalization
                    // Ideally, relative references should work
                    val diagnostics = service.diagnose(uri, content)

                    if (diagnostics.isEmpty()) {
                        out.println("OK: $arg")
                    } else {
                        for (d in diagnostics) {
                            val severity = d.severity?.toString()?.uppercase() ?: "UNKNOWN"
                            out.println(
                                "${file.path}:${d.range.start.line + 1}:${d.range.start.character + 1}: [$severity] ${d.message}",
                            )
                        }
                    }
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    logger.error("Error checking file $arg", e)
                }
            }
        }
    } finally {
        server.shutdown().get()
    }
}

fun runFormat(args: List<String>, out: PrintStream = System.out) {
    if (args.isEmpty()) {
        out.println("Usage: gls format <file> [file...]")
        exitProcess(1)
    }

    val formatter = OpenRewriteFormatter()
    for (arg in args) {
        val file = File(arg)
        if (!file.exists()) {
            System.err.println("File not found: $arg")
            continue
        }
        val input = file.readText()
        val formatted = formatter.format(input)
        out.print(formatted)
        if (!formatted.endsWith("\n")) out.println()
    }
}

fun runVersion(out: PrintStream = System.out) {
    out.println("gls version ${Version.current}")
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
    }, "gls-client-${socket.inetAddress}").start()
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

    logger.info("Language Server initialized and listening")
    launcher.startListening().get()
}

fun printHelp(out: PrintStream = System.out) {
    out.println(
        """
        gls - Groovy Language Server

        Usage: gls [command] [options]

        Commands:
          lsp [mode]         Run the language server
                             Modes: stdio (default), socket [port]
          format <file>...   Format Groovy files
          check <file>...    Run diagnostics on specified files
          execute <cmd>      Execute a custom LSP command
          version            Print the version
          help               Show this help message

        Examples:
          gls                              # Run in stdio mode (default)
          gls lsp socket 9000              # Run in socket mode on port 9000
          gls format MyFile.groovy         # Format a file
          gls check MyFile.groovy          # Check for errors
          gls execute groovy.version       # Execute a custom command
          gls version                      # Show version
        """.trimIndent(),
    )
}
