package com.github.albertocavalcante.groovylsp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.terminal.Terminal
import com.github.albertocavalcante.diagnostics.sarif.SarifRuleRegistry
import com.github.albertocavalcante.diagnostics.sarif.SarifWriter
import com.github.albertocavalcante.groovylsp.GroovyLanguageServer
import com.github.albertocavalcante.groovylsp.services.GroovyTextDocumentService
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger(CheckCommand::class.java)

/**
 * Runs diagnostics on Groovy source files.
 *
 * Supports multiple output formats:
 * - TEXT: Human-readable colored output (default)
 * - SARIF: SARIF 2.1.0 format for GitHub Code Scanning integration
 */
class CheckCommand : CliktCommand(name = "check") {
    override fun help(context: Context) = "Run diagnostics on specified files"

    private val workspace by option("-w", "--workspace")
        .file(mustExist = true, canBeFile = false)

    private val format by option("-f", "--format", help = "Output format: text (default) or sarif")
        .enum<OutputFormat>()
        .default(OutputFormat.TEXT)

    private val output by option("-o", "--output", help = "Output file (default: stdout)")
        .file(mustExist = false)

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

        // Only show progress messages for text output
        if (format == OutputFormat.TEXT) {
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
        } else {
            // Still wait for dependencies, just don't print
            server.waitForDependencies()
        }
    }

    private fun checkFiles(server: GroovyLanguageServer) {
        val service = server.getTextDocumentService() as? GroovyTextDocumentService
        if (service == null) {
            logger.error("Failed to retrieve GroovyTextDocumentService")
            throw ProgramResult(1)
        }

        // Collect all diagnostics per file
        val allDiagnostics = mutableMapOf<File, List<Diagnostic>>()
        var hasErrors = false

        runBlocking {
            for (file in files) {
                val diagnostics = checkFile(file, service)
                if (diagnostics != null) {
                    allDiagnostics[file] = diagnostics
                    if (diagnostics.any { it.severity == DiagnosticSeverity.Error }) {
                        hasErrors = true
                    }
                }
            }
        }

        // Output results based on format
        when (format) {
            OutputFormat.TEXT -> outputText(allDiagnostics)
            OutputFormat.SARIF -> outputSarif(allDiagnostics)
        }

        // Exit with error code if there were errors
        if (hasErrors) {
            throw ProgramResult(1)
        }
    }

    private suspend fun checkFile(file: File, service: GroovyTextDocumentService): List<Diagnostic>? {
        val result = runCatching {
            val uri = file.toURI()
            val content = file.readText()
            service.diagnose(uri, content)
        }

        return result
            .onFailure { e ->
                @Suppress("TooGenericExceptionCaught")
                if (e is Exception) {
                    logger.error("Error checking file ${file.path}", e)
                } else {
                    throw e
                }
            }
            .getOrNull()
    }

    private fun outputText(allDiagnostics: Map<File, List<Diagnostic>>) {
        for ((file, diagnostics) in allDiagnostics) {
            if (diagnostics.isEmpty()) {
                terminal.println(green("OK: ${file.path}"))
            } else {
                diagnostics.forEach { diagnostic ->
                    terminal.println(formatDiagnosticLine(file, diagnostic))
                }
            }
        }
    }

    private fun outputSarif(allDiagnostics: Map<File, List<Diagnostic>>) {
        val writer = SarifWriter(
            toolName = "groovy-lsp",
            toolVersion = getVersion(),
            toolUri = "https://github.com/GroovyLanguageServer/groovy-language-server",
        )

        // Register known rules
        SarifRuleRegistry.getAllRules().forEach { writer.registerRule(it) }

        // Add all diagnostics
        for ((file, diagnostics) in allDiagnostics) {
            val relativePath = workspace?.let { ws ->
                file.relativeToOrSelf(ws).invariantSeparatorsPath
            } ?: file.invariantSeparatorsPath

            writer.addDiagnostics(relativePath, diagnostics)
        }

        // Output SARIF JSON
        val json = writer.toJson(prettyPrint = true)

        val outputFile = output
        if (outputFile != null) {
            outputFile.writeText(json)
            if (format == OutputFormat.SARIF) {
                // Don't pollute SARIF output with extra messages
                logger.info("SARIF output written to ${outputFile.absolutePath}")
            }
        } else {
            println(json)
        }
    }

    private fun getVersion(): String? = javaClass.classLoader
        .getResourceAsStream("version.properties")
        ?.bufferedReader()
        ?.use { reader ->
            val props = java.util.Properties()
            props.load(reader)
            props.getProperty("version")
        }

    private fun formatDiagnosticLine(file: File, diagnostic: Diagnostic): String {
        val severityString = formatDiagnosticSeverity(diagnostic.severity)
        val line = diagnostic.range.start.line + 1
        val char = diagnostic.range.start.character + 1
        return "${file.path}:$line:$char: [$severityString] ${diagnostic.message}"
    }

    private fun formatDiagnosticSeverity(severity: DiagnosticSeverity?): String {
        val (label, style) = when (severity) {
            DiagnosticSeverity.Error -> "ERROR" to brightRed
            DiagnosticSeverity.Warning -> "WARNING" to brightYellow
            DiagnosticSeverity.Information -> "INFO" to cyan
            DiagnosticSeverity.Hint -> "HINT" to green
            else -> "UNKNOWN" to null
        }

        return if (terminal.terminalInfo.ansiLevel == AnsiLevel.NONE) {
            label
        } else {
            style?.invoke(label) ?: label
        }
    }
}
