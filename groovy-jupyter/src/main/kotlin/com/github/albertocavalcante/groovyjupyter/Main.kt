package com.github.albertocavalcante.groovyjupyter

import com.github.albertocavalcante.groovyjupyter.execution.GroovyExecutor
import com.github.albertocavalcante.groovyjupyter.kernel.KernelServer
import com.github.albertocavalcante.groovyjupyter.protocol.ConnectionFile
import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Main entry point for the Groovy Jupyter Kernel.
 *
 * Launched by Jupyter with the path to a connection file containing
 * ZMQ port numbers and HMAC key.
 *
 * Usage: java -jar groovy-jupyter.jar <connection_file>
 */
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("GroovyJupyterKernel")

    if (args.isEmpty()) {
        logger.error("Usage: groovy-jupyter <connection_file>")
        exitProcess(1)
    }

    val connectionFilePath = java.nio.file.Path.of(args[0])
    if (!java.nio.file.Files.exists(connectionFilePath)) {
        logger.error("Connection file not found: $connectionFilePath")
        exitProcess(1)
    }

    logger.info("Starting Groovy Jupyter Kernel")
    logger.info("Connection file: $connectionFilePath")

    val config = ConnectionFile.fromPath(connectionFilePath)
    logger.info("Kernel will connect on ${config.ip} with:")
    logger.info("  Shell:     ${config.shellPort}")
    logger.info("  IOPub:     ${config.iopubPort}")
    logger.info("  Control:   ${config.controlPort}")
    logger.info("  Stdin:     ${config.stdinPort}")
    logger.info("  Heartbeat: ${config.heartbeatPort}")

    // Create kernel components
    val signer = HmacSigner(config.key)
    val executor = GroovyExecutor()
    val server = KernelServer.create(config, signer, executor)

    // Register shutdown hook for graceful cleanup
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("Shutdown hook triggered")
            server.close()
        },
    )

    // Start the kernel server (blocks until shutdown)
    logger.info("Groovy Jupyter Kernel starting...")
    server.run()

    logger.info("Groovy Jupyter Kernel stopped")
}
