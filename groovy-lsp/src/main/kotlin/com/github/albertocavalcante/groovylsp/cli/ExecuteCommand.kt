package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.groovylsp.GroovyLanguageServer
import org.eclipse.lsp4j.ExecuteCommandParams
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger(ExecuteCommand::class.java)

/**
 * Executes a custom LSP command.
 */
class ExecuteCommand : CliktCommand(name = "execute") {
    override fun help(context: Context) = "Execute a custom LSP command"

    private val command by argument()

    private val commandArgs by argument()
        .multiple()

    private val terminal by requireObject<Terminal>()

    override fun run() {
        val server = GroovyLanguageServer()
        val params = ExecuteCommandParams(command, commandArgs.map { it as Any })

        try {
            val future = server.workspaceService.executeCommand(params)
            val result = future.get()
            if (result != null) {
                terminal.println(result.toString())
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("Error executing command '$command'", e)
            exitProcess(1)
        } finally {
            server.shutdown().get()
        }
    }
}
