package com.github.albertocavalcante.gvy.gls.e2e

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger("CliCommandStepExecutor")

class CliCommandStepExecutor(private val processStart: (ProcessBuilder) -> Process = ProcessBuilder::start) :
    StepExecutor<ScenarioStep.CliCommand> {
    override fun execute(step: ScenarioStep.CliCommand, context: ScenarioContext, nextStep: ScenarioStep?) {
        val command = context.interpolateString(step.command)
        val arguments = step.args.map { context.interpolateString(it) }
        val fullCommand = commandLine(command, arguments)
        logger.info { "Executing CLI command: ${fullCommand.joinToString(" ")}" }
        val builder = processBuilder(fullCommand, context)

        SessionResources().use { resources ->
            // TODO(#1669): Bound captured bytes while draining both output streams.
            //   See: https://github.com/albertocavalcante/gvy/issues/1669
            val capture = Files.createTempDirectory("gvy-cli-")
            resources.own(AutoCloseable { WorkspaceFixture.cleanup(capture) })
            val outputFile = capture.resolve("stdout.log").toFile()
            val errorFile = capture.resolve("stderr.log").toFile()
            builder.redirectOutput(outputFile)
            builder.redirectError(errorFile)

            val process = processStart(builder)
            resources.own(CliProcess(process, resources.interruption))
            context.checkActive()
            if (!process.waitFor(step.timeoutSeconds, TimeUnit.SECONDS)) {
                throw TimeoutException("CLI command timed out after ${step.timeoutSeconds}s: $fullCommand")
            }
            context.checkActive()
            acceptResult(step, context, process.exitValue(), outputFile.readText(), errorFile.readText())
        }
    }

    private fun commandLine(command: String, arguments: List<String>): List<String> {
        if (!command.startsWith("gls") && !command.startsWith("jenkins")) return command.split(" ") + arguments

        // Priority: execJar (Bazel), configured application script (Gradle), then the existing fallback.
        val execJar = System.getProperty("groovy.lsp.e2e.execJar")?.let(BazelRunfiles::resolveForSubprocess)
        val binary = System.getProperty("groovy.lsp.binary")
            ?: "./groovy-lsp/build/install/groovy-lsp/bin/groovy-lsp"
        val invocation = if (!execJar.isNullOrBlank()) {
            mutableListOf(resolveJavaBinary(), "-Xmx1g", "-jar", execJar)
        } else {
            mutableListOf(binary)
        }
        if (command.contains(" ")) {
            val parts = command.split(" ")
            invocation.addAll(if (parts.first() == "gls") parts.drop(1) else parts)
        } else if (command != "gls") {
            invocation.add(command)
        }
        return invocation + arguments
    }

    private fun processBuilder(command: List<String>, context: ScenarioContext): ProcessBuilder {
        val builder = ProcessBuilder(command).directory(context.workspace.rootDir.toFile())
        // Gradle application scripts consume JAVA_OPTS; direct JVM invocation uses the same explicit cap.
        builder.environment()["JAVA_OPTS"] = "${builder.environment()["JAVA_OPTS"].orEmpty()} -Xmx1g".trim()
        val javaHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME")
        if (javaHome != null) builder.environment()["JAVA_HOME"] = javaHome
        return builder
    }

    private fun acceptResult(
        step: ScenarioStep.CliCommand,
        context: ScenarioContext,
        exitCode: Int,
        stdout: String,
        stderr: String,
    ) {
        logger.info { "CLI exit code: $exitCode" }
        if (stderr.isNotEmpty()) logger.warn { "CLI stderr: $stderr" }
        if (exitCode != step.expectExitCode) {
            throw AssertionError(
                "CLI command failed with exit code $exitCode (expected ${step.expectExitCode})" +
                    "\nStdout: $stdout\nStderr: $stderr",
            )
        }
        val result = buildJsonObject {
            put("exitCode", exitCode)
            put("stdout", stdout)
            put("stderr", stderr)
        }
        context.lastResult = result
        step.saveAs?.let { context.saveResult(it, result) }
        step.checks.forEach { context.evaluateCheck(result, it) }
    }
}

/** Owns the child and all three process streams, including on interrupted waits and output/assertion failures. */
private class CliProcess(private val process: Process, private val interruption: CleanupInterruption) : AutoCloseable {
    override fun close() {
        // TODO(#1670): Release owned descendants while preserving shared daemons and unrelated processes.
        //   See: https://github.com/albertocavalcante/gvy/issues/1670
        AutoCloseable {
            process.inputStream.use {
                process.errorStream.use { process.outputStream.close() }
            }
        }.use {
            if (process.isAlive) {
                val start = System.nanoTime()
                process.destroyForcibly()
                check(awaitTermination(start) && !process.isAlive) {
                    "CLI process termination is not confirmed after cancellation"
                }
            }
        }
    }

    private fun awaitTermination(start: Long): Boolean {
        val budget = TimeUnit.SECONDS.toNanos(CLI_TERMINATION_TIMEOUT_SECONDS)
        var remaining = budget - (System.nanoTime() - start)
        while (remaining > 0) {
            try {
                return interruption.observe { process.waitFor(remaining, TimeUnit.NANOSECONDS) } || !process.isAlive
            } catch (_: InterruptedException) {
                // observe records the signal for restoration after all cleanup; retry only within the same budget.
            }
            remaining = budget - (System.nanoTime() - start)
        }
        return !process.isAlive
    }
}

private const val CLI_TERMINATION_TIMEOUT_SECONDS = 5L
