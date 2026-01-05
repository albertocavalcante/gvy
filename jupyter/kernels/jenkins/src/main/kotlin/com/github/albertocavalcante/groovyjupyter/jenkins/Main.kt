package com.github.albertocavalcante.groovyjupyter.jenkins

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
import java.util.concurrent.CancellationException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("JenkinsKernelMain")

    if (args.isEmpty()) {
        logger.error("Usage: java -jar jenkins-kernel.jar <connection_file>")
        exitProcess(1)
    }

    val connectionFilePath = args[0]
    logger.info("Starting Jenkins Kernel with connection file: {}", connectionFilePath)

    runCatching {
        val connectionFileContent = File(connectionFilePath).readText()
        val connectionFile = ConnectionFile.parse(connectionFileContent)

        // Initialize Crypto
        val signer = HmacSigner(connectionFile.key)

        // Initialize Connection
        val connection = JupyterConnection(connectionFile, signer)

        // Initialize Executors specific to this kernel
        val executor = JenkinsExecutor()

        // Initialize Handlers
        val heartbeatHandler = HeartbeatHandler(connection.heartbeatSocket)

        lateinit var server: KernelServer
        val handlers = listOf(
            ExecuteHandler(executor),
            KernelInfoHandler(
                languageName = "jenkins-groovy",
                languageVersion = "2.4.21", // Hardcoded for now
            ),
            ShutdownHandler { server.shutdown() },
        )

        // Start Server
        server = KernelServer(connection, handlers, heartbeatHandler)
        server.use {
            it.run()
        }
    }.getOrElse { throwable ->
        when (throwable) {
            is CancellationException -> throw throwable
            is Error -> throw throwable
        }
        logger.error("Fatal error starting kernel", throwable)
        exitProcess(1)
    }
}
