// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Wire types are deliberately plain JSON values for non-VS Code clients. */
data class PrepareFileExecutionParams(
    val uri: String = "",
    val version: Int = -1,
    val action: String = "",
    val trusted: Boolean = false,
    val contextId: String? = null,
    val forceProject: Boolean = false,
    val forceEntryPoint: Boolean = false,
    val entryPoint: String? = null,
    val preparationToken: String? = null,
    val launchToken: String? = null,
    val savedScopeDirectory: String? = null,
    val options: ExecutionOptions = ExecutionOptions(),
)

data class ExecutionOptions(
    val javaExecutable: String? = null,
    val projectJavaHome: String? = null,
    val explicitRuntime: List<String>? = null,
    val localRuntime: List<String>? = null,
    val bundledRuntime: List<String>? = null,
    val arguments: List<String> = emptyList(),
    val jvmArguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val classpath: List<String> = emptyList(),
    val outputDirectory: String = "",
)

data class ExecutionProcess(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String>,
)

data class ExecutionChoice(val id: String, val label: String)

data class FileExecutionResponse(
    val kind: String,
    val reason: String? = null,
    val reasonCode: String? = null,
    val alternativeCommand: String? = null,
    val choiceType: String? = null,
    val choices: List<ExecutionChoice>? = null,
    val contextLabel: String? = null,
    val runtimeLabel: String? = null,
    val token: String? = null,
    val launchToken: String? = null,
    val process: ExecutionProcess? = null,
    val scopeDirectory: String? = null,
    val compile: ExecutionProcess? = null,
    val launch: ExecutionProcess? = null,
    val outputDirectory: String? = null,
)

/** An already observed build model; sourceFiles is authoritative membership, not a source root. */
data class FileExecutionContext(
    val id: String,
    val label: String,
    val directory: Path,
    val sourceFiles: Set<Path>,
    val compileClasspath: List<String>,
    val runtimeClasspath: List<String>,
    val runtime: List<String>?,
    val runtimeLabel: String?,
    val generation: String,
    val preparation: ExecutionProcess,
    val scopeDirectory: Path = directory,
    val complete: Boolean = true,
    val inputFiles: Set<Path> = sourceFiles,
)

private data class PlanSelection(
    val request: PrepareFileExecutionParams,
    val source: SavedSource,
    val context: FileExecutionContext?,
    val entry: FileEntryPoint?,
    val contexts: List<FileExecutionContext>,
    val workspaceRoot: Path?,
)

private data class ReadyInput(
    val request: PrepareFileExecutionParams,
    val source: SavedSource,
    val context: FileExecutionContext?,
    val entry: FileEntryPoint?,
    val runtime: SelectedRuntime,
    val output: Path,
    val workspaceRoot: Path?,
    val token: String,
)

class FileExecutionPlanner {
    fun prepare(
        request: PrepareFileExecutionParams,
        content: String,
        contexts: List<FileExecutionContext>,
        workspaceRoot: Path? = null,
    ): FileExecutionResponse = when {
        !request.trusted -> unavailable("Trust the workspace before running or compiling files.")
        request.action !in listOf("run", "compile") -> unavailable("Unknown execution action.")
        else -> savedSource(request.uri, content).flatMap { source ->
            selectContext(request, source.path, contexts).flatMap { context ->
                selectEntry(request, source.entries).flatMap { entry ->
                    prepareProcesses(PlanSelection(request, source, context, entry, contexts, workspaceRoot))
                }
            }
        }.response()
    }

    private fun prepareProcesses(selection: PlanSelection): ExecutionStep<FileExecutionResponse> =
        selectRuntime(selection.request.options, selection.context).flatMap { runtime ->
            outputPath(selection.request.options.outputDirectory).flatMap { output ->
                val input = ReadyInput(
                    selection.request,
                    selection.source,
                    selection.context,
                    selection.entry,
                    runtime,
                    output,
                    selection.workspaceRoot,
                    "",
                )
                validatePreparation(input, selection.contexts).map { token -> readyPlan(input.copy(token = token)) }
            }
        }

    private fun selectContext(
        request: PrepareFileExecutionParams,
        source: Path,
        contexts: List<FileExecutionContext>,
    ): ExecutionStep<FileExecutionContext?> {
        val owned = contexts.filter { context -> context.sourceFiles.any { executionPath(it) == source } }
        return when {
            request.forceProject && request.contextId == null -> ExecutionStep.Stop(
                choices(
                    "context",
                    listOf(ExecutionChoice("standalone", "Standalone")) +
                        contexts.map { ExecutionChoice(it.id, it.label) },
                ),
            )
            request.contextId == "standalone" -> ExecutionStep.Value(null)
            request.contextId != null -> contexts.firstOrNull { it.id == request.contextId }
                ?.let { ExecutionStep.Value(it) }
                ?: stop("The selected project context is no longer available.", "invalidContext")
            owned.size > 1 -> ExecutionStep.Stop(choices("context", owned.map { ExecutionChoice(it.id, it.label) }))
            else -> ExecutionStep.Value(owned.singleOrNull())
        }
    }

    private fun selectEntry(
        request: PrepareFileExecutionParams,
        entries: List<FileEntryPoint>,
    ): ExecutionStep<FileEntryPoint?> {
        if (request.action != "run") return ExecutionStep.Value(null)
        if (entries.isEmpty()) {
            val code = if (request.entryPoint != null) "invalidEntryPoint" else null
            return ExecutionStep.Stop(
                FileExecutionResponse(
                    kind = "unavailable",
                    reason = "No runnable entry point; use Compile File.",
                    reasonCode = code,
                    alternativeCommand = "groovy.compileFile",
                ),
            )
        }
        if (request.entryPoint != null) {
            return entries.firstOrNull { it.id == request.entryPoint }?.let { ExecutionStep.Value(it) }
                ?: stop("The selected entry point is no longer available.", "invalidEntryPoint")
        }
        if (request.forceEntryPoint || entries.size > 1) {
            return ExecutionStep.Stop(choices("entryPoint", entries.map { ExecutionChoice(it.id, it.label) }))
        }
        return ExecutionStep.Value(entries.single())
    }

    private fun selectRuntime(
        options: ExecutionOptions,
        context: FileExecutionContext?,
    ): ExecutionStep<SelectedRuntime> {
        projectRuntimeProblem(context)?.let { return stop(it) }
        val runtime = options.explicitRuntime ?: context?.runtime
            ?: if (context == null) options.localRuntime ?: options.bundledRuntime else null
        return FileRuntimeInspector.inspect(runtime)
    }

    private fun projectRuntimeProblem(context: FileExecutionContext?): String? = when {
        context == null -> null
        !context.complete -> "The selected project's execution classpath is incomplete; refresh its Gradle model."
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
            "Gradle project execution is not yet supported on Windows."
        context.runtime.isNullOrEmpty() ->
            "The project Groovy runtime is unresolved or conflicting. Fix its Gradle runtime dependencies; " +
                "an explicit runtime cannot safely replace unidentified project JARs."
        else -> null
    }

    private fun outputPath(value: String): ExecutionStep<Path> {
        val output = try {
            Path.of(value)
        } catch (_: java.nio.file.InvalidPathException) {
            return stop("The output directory path is invalid.")
        }
        if (!output.isAbsolute) return stop("The output directory must be an absolute managed path.")
        return ExecutionStep.Value(output)
    }

    private fun validatePreparation(input: ReadyInput, contexts: List<FileExecutionContext>): ExecutionStep<String> {
        val inputs = fingerprintInputs(input.context?.scopeDirectory, contexts, input.runtime.jars)
            ?: return stop("Cannot verify execution inputs; run again after fixing file access.")
        val token = fingerprint(
            input.request.copy(preparationToken = null, launchToken = null).toString() +
                input.source.content + input.context.toString() + inputs,
        )
        return validateOutput(input, token).flatMap { preparationResult(input, token) }
    }

    private fun validateOutput(input: ReadyInput, token: String): ExecutionStep<Unit> {
        val request = input.request
        if (request.launchToken != null && request.launchToken != token) {
            return stop("Execution inputs changed before launch; run again.")
        }
        return try {
            val output = input.output
            val exists = Files.exists(output)
            val notDirectory = exists && !Files.isDirectory(output)
            val requiresEmpty = exists && !notDirectory && request.launchToken == null
            val notEmpty = requiresEmpty && Files.list(output).use { it.findAny().isPresent }
            if (notDirectory || notEmpty) {
                stop("Choose a fresh, empty managed output directory.")
            } else {
                ExecutionStep.Value(Unit)
            }
        } catch (_: java.io.IOException) {
            stop("Cannot inspect the managed output directory.")
        }
    }

    private fun preparationResult(input: ReadyInput, token: String): ExecutionStep<String> {
        val context = input.context
        val prepared = input.request.preparationToken
        return when {
            context != null && prepared == null -> ExecutionStep.Stop(
                FileExecutionResponse(
                    kind = "preparation",
                    contextLabel = context.label,
                    token = token,
                    process = context.preparation,
                    scopeDirectory = context.scopeDirectory.toString(),
                ),
            )
            context != null && prepared != token ->
                stop("The document, configuration, or project changed during preparation; run again.")
            else -> ExecutionStep.Value(token)
        }
    }

    private fun readyPlan(input: ReadyInput): FileExecutionResponse {
        val options = input.request.options
        val projectRuntime = input.context?.runtime.orEmpty().toSet()
        val projectCompileClasspath = input.context?.compileClasspath.orEmpty().filterNot { it in projectRuntime }
        val projectRuntimeClasspath = input.context?.runtimeClasspath.orEmpty().filterNot { it in projectRuntime }
        val compileClasspath = input.runtime.jars + projectCompileClasspath + options.classpath
        val runtimeClasspath = input.runtime.jars + projectRuntimeClasspath + options.classpath
        val workingDirectory = options.workingDirectory ?: input.context?.directory?.toString()
            ?: input.workspaceRoot?.toString() ?: input.source.path.parent.toString()
        val compile = compileProcess(input, compileClasspath, workingDirectory)
        val launch = launchProcess(input, runtimeClasspath, workingDirectory)
        val runtimeLabel = when {
            input.runtime.version != null -> "Groovy ${input.runtime.version}"
            options.explicitRuntime != null -> "Configured Groovy"
            input.context != null -> input.context.runtimeLabel
            options.localRuntime != null -> "Local Groovy"
            else -> "Groovy 4.0.33"
        }
        return FileExecutionResponse(
            kind = "ready",
            contextLabel = input.context?.label ?: "Standalone",
            runtimeLabel = runtimeLabel,
            compile = compile,
            launch = launch,
            launchToken = input.token,
            outputDirectory = input.output.toString(),
        )
    }

    private fun compileProcess(input: ReadyInput, classpath: List<String>, directory: String): ExecutionProcess? {
        if (input.request.launchToken != null || (input.request.action != "compile" && input.entry?.id == "script")) {
            return null
        }
        val options = input.request.options
        return ExecutionProcess(
            options.javaExecutable ?: "java",
            options.jvmArguments + listOf(
                "-cp",
                classpath.joinToString(File.pathSeparator),
                "org.codehaus.groovy.tools.FileSystemCompiler",
                "-d",
                input.output.toString(),
                input.source.path.toString(),
            ),
            directory,
            options.environment,
        )
    }

    private fun launchProcess(input: ReadyInput, runtimeClasspath: List<String>, directory: String): ExecutionProcess? {
        if (input.request.action != "run") return null
        val options = input.request.options
        val script = input.entry?.id == "script"
        val launcher = if (script) "groovy.ui.GroovyMain" else input.entry?.id.orEmpty()
        val classpath = if (script) runtimeClasspath else listOf(input.output.toString()) + runtimeClasspath
        val sourceArgument = if (script) listOf(input.source.path.toString()) else emptyList()
        return ExecutionProcess(
            options.javaExecutable ?: "java",
            options.jvmArguments + listOf("-cp", classpath.joinToString(File.pathSeparator), launcher) +
                sourceArgument + options.arguments,
            directory,
            options.environment,
        )
    }

    private fun choices(type: String, values: List<ExecutionChoice>) =
        FileExecutionResponse(kind = "choices", choiceType = type, choices = values)

    private fun stop(reason: String, reasonCode: String? = null) = ExecutionStep.Stop(unavailable(reason, reasonCode))

    private fun unavailable(reason: String, reasonCode: String? = null) =
        FileExecutionResponse(kind = "unavailable", reason = reason, reasonCode = reasonCode)

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
