package com.github.albertocavalcante.gvy.gls.e2e

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val CLI_OUTPUT_LIMIT_PROPERTY = "groovy.lsp.e2e.cliOutputLimitBytes"
internal const val MAX_CLI_OUTPUT_BYTES = 16 * 1024 * 1024
private const val DEFAULT_CLI_OUTPUT_BYTES = 1024 * 1024
private const val READ_BUFFER_BYTES = 8192
private const val DIAGNOSTIC_SAMPLE_BYTES = 256
private const val READER_CLEANUP_SECONDS = 5L

internal fun configuredCliOutputLimit(configured: String? = System.getProperty(CLI_OUTPUT_LIMIT_PROPERTY)): Int {
    if (configured == null) return DEFAULT_CLI_OUTPUT_BYTES
    val bytes = configured.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }?.toIntOrNull()
    require(bytes != null && bytes in 1..MAX_CLI_OUTPUT_BYTES) {
        "$CLI_OUTPUT_LIMIT_PROPERTY must be a decimal integer from 1 to $MAX_CLI_OUTPUT_BYTES"
    }
    return bytes
}

internal class CliOutputLimitExceeded(channel: String, limit: Int, observed: Int, sample: String) :
    IllegalStateException() {
    var cleanupConfirmed = false
    private val detail = "CLI $channel exceeded byte limit $limit; observed at least $observed bytes; " +
        "sample (up to $DIAGNOSTIC_SAMPLE_BYTES bytes): $sample"
    override val message: String
        get() = "$detail; direct process and reader cleanup " +
            if (cleanupConfirmed) "confirmed" else "unconfirmed (see suppressed failures)"
}

internal data class CliCommandOutput(val exitCode: Int, val stdout: String, val stderr: String)

/** Owns concurrent pipe readers. Register before the process so process termination/stream close precedes joining. */
internal class CliOutputCapture(
    private val limitBytes: Int,
    closeTimeout: Duration = Duration.ofSeconds(READER_CLEANUP_SECONDS),
    threadFactory: ThreadFactory? = null,
) : AutoCloseable {
    private val executor = OwnedExecutor("groovy-lsp-e2e-cli-output", closeTimeout, threadFactory)

    init {
        require(limitBytes in 1..MAX_CLI_OUTPUT_BYTES) { "CLI output limit must be in 1..$MAX_CLI_OUTPUT_BYTES" }
    }

    fun await(process: Process, timeoutSeconds: Long): CliCommandOutput {
        val outcome = CompletableFuture<CliCommandOutput>()
        val stdout = watch(outcome) { read("stdout", process.inputStream) }
        val stderr = watch(outcome) { read("stderr", process.errorStream) }
        val exited = watch(outcome) {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw TimeoutException("CLI command timed out after ${timeoutSeconds}s")
            }
            process.exitValue()
        }
        exited.thenCombine(stdout) { code, output -> code to output }
            .thenCombine(stderr) { (code, output), errors -> CliCommandOutput(code, output, errors) }
            .thenAccept(outcome::complete)
        return try {
            outcome.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun <T> watch(outcome: CompletableFuture<CliCommandOutput>, action: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(action, executor.service).whenComplete { _, failure ->
            if (failure != null) {
                outcome.completeExceptionally(if (failure is CompletionException) failure.cause ?: failure else failure)
            }
        }

    private fun read(channel: String, stream: InputStream): String {
        val captured = CapturedBytes(minOf(limitBytes, READ_BUFFER_BYTES))
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            // Read only one byte beyond the budget: retained data and the reported lower bound stay bounded.
            val read = stream.read(buffer, 0, minOf(buffer.size, limitBytes - captured.size() + 1))
            if (read < 0) return captured.toString(UTF_8)
            val retained = minOf(read, limitBytes - captured.size())
            captured.write(buffer, 0, retained)
            if (retained < read) {
                throw CliOutputLimitExceeded(channel, limitBytes, limitBytes + 1, captured.sample())
            }
        }
    }

    override fun close() = executor.close()
}

private class CapturedBytes(initialCapacity: Int) : ByteArrayOutputStream(initialCapacity) {
    fun sample(): String = String(buf, 0, minOf(count, DIAGNOSTIC_SAMPLE_BYTES), UTF_8)
}
