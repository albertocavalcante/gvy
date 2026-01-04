package com.github.albertocavalcante.groovyjupyter.groovy

import com.github.albertocavalcante.groovyjupyter.handlers.ExecuteHandler
import com.github.albertocavalcante.groovyjupyter.handlers.HeartbeatHandler
import com.github.albertocavalcante.groovyjupyter.handlers.KernelInfoHandler
import com.github.albertocavalcante.groovyjupyter.handlers.ShutdownHandler
import com.github.albertocavalcante.groovyjupyter.kernel.KernelServer
import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import com.github.albertocavalcante.groovyjupyter.zmq.JupyterConnection
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("GroovyKernelMain")

    if (args.isEmpty()) {
        logger.error("Usage: java -jar groovy-kernel.jar <connection_file>")
        exitProcess(1)
    }

    val connectionFilePath = args[0]
    logger.info("Starting Groovy Kernel with connection file: {}", connectionFilePath)

    runCatching {
        val connectionFileContent = File(connectionFilePath).readText()
        val connectionFile = ConnectionFile.parse(connectionFileContent)

        // Initialize Crypto
        val signer = HmacSigner(connectionFile.key)

        // Initialize Connection
        val connection = JupyterConnection(connectionFile, signer)

        // Initialize Executors specific to this kernel
        val executor = GroovyKernelExecutor()

        // Initialize Handlers
        // Heartbeat is handled separately by the server
        val heartbeatHandler = HeartbeatHandler(connection.heartbeatSocket)

        lateinit var server: KernelServer
        val handlers = listOf(
            ExecuteHandler(executor),
            KernelInfoHandler(
                languageName = "groovy",
                languageVersion = groovy.lang.GroovySystem.getVersion(),
            ),
            ShutdownHandler { server.shutdown() },
        )

        // Start Server
        server = KernelServer(connection, handlers, heartbeatHandler)
        server.use {
            it.run()
        }
    }.getOrElse { throwable ->
        if (throwable is Error) throw throwable
        logger.error("Fatal error starting kernel", throwable)
        exitProcess(1)
    }
}
