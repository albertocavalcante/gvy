package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.groovylsp.GroovyLanguageServer
import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger(CheckCommand::class.java)

/**
 * Runs diagnostics on Groovy source files with colored output.
 */
class CheckCommand : CliktCommand(name = "check") {
    override fun help(context: Context) = "Run diagnostics on specified files"

    private val workspace by option("-w", "--workspace")
        .file(mustExist = true, canBeFile = false)

    private val files by argument()
        .file(mustExist = true, canBeDir = false)
        .multiple(required = true)

    private val terminal by requireObject<Terminal>()

    override fun run() {
        val server = GroovyLanguageServer()
        try {
            initializeWorkspace(server)
            checkFiles(server)
        } finally {
            server.shutdown().get()
        }
    }

    private fun initializeWorkspace(server: GroovyLanguageServer) {
        val ws = workspace ?: return

        val params = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(ws.toURI().toString(), ws.name))
            capabilities = ClientCapabilities()
        }

        server.initialize(params).get()
        server.initialized(InitializedParams())

        terminal.println(cyan("Resolving dependencies for ${ws.absolutePath}..."))
        if (server.waitForDependencies()) {
            terminal.println(green("Dependencies resolved successfully."))
        } else {
            terminal.println(
                brightYellow(
                    "Warning: Dependency resolution failed or timed out. " +
                        "Checking with limited context.",
                ),
            )
        }
    }

    private fun checkFiles(server: GroovyLanguageServer) {
        val service = server.getTextDocumentService() as? GroovyTextDocumentService
        if (service == null) {
            logger.error("Failed to retrieve GroovyTextDocumentService")
            throw ProgramResult(1)
        }

        runBlocking {
            for (file in files) {
                checkFile(file, service)
            }
        }
    }

    private suspend fun checkFile(file: File, service: GroovyTextDocumentService) {
        try {
            val uri = file.toURI()
            val content = file.readText()
            val diagnostics = service.diagnose(uri, content)

            if (diagnostics.isEmpty()) {
                terminal.println(green("OK: ${file.path}"))
            } else {
                for (d in diagnostics) {
                    val (label, style) = when (d.severity) {
                        DiagnosticSeverity.Error -> "ERROR" to brightRed
                        DiagnosticSeverity.Warning -> "WARNING" to brightYellow
                        DiagnosticSeverity.Information -> "INFO" to cyan
                        DiagnosticSeverity.Hint -> "HINT" to green
                        else -> "UNKNOWN" to null
                    }

                    val severityString = if (terminal.terminalInfo.ansiLevel == AnsiLevel.NONE) {
                        label
                    } else {
                        style?.invoke(label) ?: label
                    }

                    val line = d.range.start.line + 1
                    val char = d.range.start.character + 1
                    terminal.println("${file.path}:$line:$char: [$severityString] ${d.message}")
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error("Error checking file ${file.path}", e)
        }
    }
}
