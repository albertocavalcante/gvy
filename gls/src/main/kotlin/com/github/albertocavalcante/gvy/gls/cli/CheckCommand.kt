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
import com.github.albertocavalcante.gvy.build.ResolutionStatus
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
import java.util.concurrent.ExecutionException

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
    private val dependencyStatus: (GroovyLanguageServer) -> ResolutionStatus? = { it.waitForDependencyStatus() },
    private val startWorkspace: (GroovyLanguageServer, InitializeParams) -> Unit = { server, params ->
        server.initialize(params).get()
        server.initialized(InitializedParams())
    },
    private val stopServer: (GroovyLanguageServer) -> Unit = { it.shutdown().get() },
) : CliktCommand(name = "check") {
    constructor() : this(analyze = { file, service -> service.diagnoseResult(file.toURI(), file.readText()) })
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
        AutoCloseable { stopServer(server) }.use {
            val workspaceFailure = runCatching { initializeWorkspace(server) }.getOrElse { failure ->
                val cause = (failure as? ExecutionException)?.cause ?: failure
                when (cause) {
                    is CancellationException -> throw cause
                    is InterruptedException -> throw cause
                    is Exception -> "Workspace initialization failed: ${cause.message ?: cause.javaClass.name}"
                    else -> throw cause
                }
            }
            checkFiles(server, workspaceFailure)
        }
    }

    private fun initializeWorkspace(server: GroovyLanguageServer): String? {
        val ws = workspace ?: return null

        val params = InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(ws.toURI().toString(), ws.name))
            capabilities = ClientCapabilities()
        }

        startWorkspace(server, params)

        if (format == OutputFormat.TEXT) {
            terminal.println(cyan("Resolving dependencies for ${ws.absolutePath}..."))
        }
        val failure = when (val status = dependencyStatus(server)) {
            ResolutionStatus.Success -> null
            is ResolutionStatus.Warning -> "Workspace dependency resolution incomplete (${status.code}): ${status.message}"
            is ResolutionStatus.Failed -> "Workspace dependency resolution failed (${status.code}): ${status.message}"
            null -> "Workspace dependency resolution failed or timed out"
        }
        if (format == OutputFormat.TEXT && failure == null) {
            terminal.println(green("Dependencies resolved successfully."))
        }
        return failure
    }

    private fun checkFiles(server: GroovyLanguageServer, workspaceFailure: String?) {
        val service = server.getTextDocumentService() as? GroovyTextDocumentService
        if (service == null) {
            logger.error { "Failed to retrieve GroovyTextDocumentService" }
            throw ProgramResult(1)
        }

        val outcomes = runBlocking {
            files.map { file ->
                val result = checkFile(file, service)
                if (workspaceFailure == null) result else result.copy(
                    failures = result.failures + FileFailure("workspace", workspaceFailure),
                )
            }
        }

        when (format) {
            OutputFormat.TEXT -> outputText(outcomes)
            OutputFormat.SARIF -> outputSarif(outcomes)
        }

        if (outcomes.any { outcome ->
                outcome.failures.isNotEmpty() || outcome.diagnostics.any { it.severity == DiagnosticSeverity.Error }
            }
        ) {
            throw ProgramResult(1)
        }
    }

    private data class FileFailure(val id: String, val message: String)

    private data class FileOutcome(val file: File, val diagnostics: List<Diagnostic>, val failures: List<FileFailure>)

    private suspend fun checkFile(file: File, service: GroovyTextDocumentService): FileOutcome =
        runCatching { analyze(file, service) }.fold(
            onSuccess = { result ->
                FileOutcome(file, result.diagnostics, result.failures.map { failure ->
                    FileFailure(
                        "provider:${failure.providerId}",
                        "Provider ${failure.providerId} failed (${failure.exceptionType}): ${failure.message ?: "no details"}",
                    )
                })
            },
            onFailure = { failure ->
                when (failure) {
                    is CancellationException -> throw failure
                    is InterruptedException -> throw failure
                    is Exception -> {
                        logger.error(failure) { "Error checking file ${file.path}" }
                        FileOutcome(
                            file,
                            emptyList(),
                            listOf(FileFailure("analysis", "${failure.javaClass.name}: ${failure.message ?: "no details"}")),
                        )
                    }
                    else -> throw failure
                }
            },
        )

    private fun outputText(outcomes: List<FileOutcome>) {
        for (outcome in outcomes) {
            if (outcome.diagnostics.isEmpty() && outcome.failures.isEmpty()) {
                terminal.println(green("OK: ${outcome.file.path}"))
            }
            outcome.diagnostics.forEach { diagnostic ->
                terminal.println(formatDiagnosticLine(outcome.file, diagnostic))
            }
            outcome.failures.forEach { failure ->
                terminal.println(brightRed("FAILED: ${outcome.file.path}: ${failure.message}"))
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
            writer.addFileCheck(outcome.file.toURI().toASCIIString(), outcome.failures.map { it.id })
            writer.addDiagnostics(relativePath, outcome.diagnostics)
            outcome.failures.forEach { writer.addExecutionFailure(relativePath, it.message) }
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
