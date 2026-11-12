package com.github.albertocavalcante.gvy.gls.cli

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
import com.github.albertocavalcante.gvy.diagnostics.sarif.SarifRuleRegistry
import com.github.albertocavalcante.gvy.diagnostics.sarif.SarifWriter
import com.github.albertocavalcante.gvy.gls.GroovyLanguageServer
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsCollectionResult
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.WorkspaceFolder
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * Runs diagnostics on Groovy source files.
 *
 * Supports multiple output formats:
 * - TEXT: Human-readable colored output (default)
 * - SARIF: SARIF 2.1.0 format for GitHub Code Scanning integration
 */
class CheckCommand internal constructor(
    private val analyze: suspend (File, GroovyTextDocumentService) -> DiagnosticsCollectionResult,
) : CliktCommand(name = "check") {
    constructor() : this({ file, service -> service.diagnoseResult(file.toURI(), file.readText()) })
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
            logger.error { "Failed to retrieve GroovyTextDocumentService" }
            throw ProgramResult(1)
        }

        val outcomes = runBlocking { files.map { checkFile(it, service) } }

        when (format) {
            OutputFormat.TEXT -> outputText(outcomes)
            OutputFormat.SARIF -> outputSarif(outcomes)
        }

        if (outcomes.any { it.failures.isNotEmpty() || it.diagnostics.any { d -> d.severity == DiagnosticSeverity.Error } }) {
            throw ProgramResult(1)
        }
    }

    private data class FileOutcome(val file: File, val diagnostics: List<Diagnostic>, val failures: List<String>)

    private suspend fun checkFile(file: File, service: GroovyTextDocumentService): FileOutcome = try {
        val result = analyze(file, service)
        FileOutcome(file, result.diagnostics, result.failures.map { failure ->
            "Provider ${failure.providerId} failed (${failure.exceptionType}): ${failure.message ?: "no details"}"
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "Error checking file ${file.path}" }
        FileOutcome(file, emptyList(), listOf("${e.javaClass.name}: ${e.message ?: "no details"}"))
    }

    private fun outputText(outcomes: List<FileOutcome>) {
        for (outcome in outcomes) {
            if (outcome.diagnostics.isEmpty() && outcome.failures.isEmpty()) {
                terminal.println(green("OK: ${outcome.file.path}"))
            }
            outcome.diagnostics.forEach { diagnostic ->
                terminal.println(formatDiagnosticLine(outcome.file, diagnostic))
            }
            outcome.failures.forEach { failure ->
                terminal.println(brightRed("FAILED: ${outcome.file.path}: $failure"))
            }
        }
    }

    private fun outputSarif(outcomes: List<FileOutcome>) {
        val writer = SarifWriter(toolName = "groovy-lsp", toolVersion = getVersion())
        SarifRuleRegistry.getAllRules().forEach { writer.registerRule(it) }
        for (outcome in outcomes) {
            val relativePath = workspace?.let { ws ->
                outcome.file.relativeToOrSelf(ws).invariantSeparatorsPath
            } ?: outcome.file.invariantSeparatorsPath
            writer.addDiagnostics(relativePath, outcome.diagnostics)
            outcome.failures.forEach { writer.addExecutionFailure(relativePath, it) }
        }

        val json = writer.toJson(prettyPrint = true)
        val outputFile = output
        if (outputFile != null) {
            outputFile.writeText(json)
            logger.info { "SARIF output written to ${outputFile.absolutePath}" }
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
