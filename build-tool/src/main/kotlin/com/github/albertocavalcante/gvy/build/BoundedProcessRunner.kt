// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Runs a short-lived build-tool command with a bounded wait.
 *
 * Standard input is closed immediately (so prompts cannot block), stderr is merged into the captured
 * output, and on timeout the whole process tree is killed so wrapper scripts cannot leave orphans.
 *
 * Captured output is bounded: the first [HEAD_CHARS] and last [TAIL_CHARS] characters are kept and the middle
 * is replaced by a truncation marker, so a chatty command cannot grow memory or logs without limit.
 */
internal object BoundedProcessRunner {

    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean)

    private val READER_JOIN_TIMEOUT = 5.seconds

    internal const val HEAD_CHARS = 16 * 1024
    internal const val TAIL_CHARS = 48 * 1024
    private const val READ_BUFFER_CHARS = 8 * 1024

    fun run(command: List<String>, directory: Path, timeout: Duration): Result {
        val process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()

        val output = BoundedOutput(HEAD_CHARS, TAIL_CHARS)
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(READ_BUFFER_CHARS)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.append(buffer, count)
                    }
                }
            }
        }, "bounded-process-reader").apply { isDaemon = true }
        reader.start()

        try {
            val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!finished) {
                killTree(process)
                stopReader(process, reader)
                return Result(null, output.toString(), timedOut = true)
            }
            stopReader(process, reader)
            return Result(process.exitValue(), output.toString(), timedOut = false)
        } catch (e: InterruptedException) {
            killTree(process)
            // Keep the interrupt for the caller, but do not leave the reader thread or stream behind.
            stopReader(process, reader)
            throw e
        }
    }

    /** Gives the reader a bounded time to drain, then closes the stream and interrupts it if it is still blocked. */
    private fun stopReader(process: Process, reader: Thread) {
        var interrupted = false
        try {
            reader.join(READER_JOIN_TIMEOUT.toJavaDuration().toMillis())
        } catch (ignored: InterruptedException) {
            interrupted = true
        }
        if (reader.isAlive) {
            runCatching { process.inputStream.close() }
            reader.interrupt()
            try {
                reader.join(READER_JOIN_TIMEOUT.toJavaDuration().toMillis())
            } catch (ignored: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun killTree(process: Process) {
        // Snapshot descendants before killing the parent, which would reparent them.
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}

/** Thread-safe capture that keeps the first [headLimit] and the last [tailLimit] characters. */
private class BoundedOutput(private val headLimit: Int, private val tailLimit: Int) {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var dropped = 0L

    @Synchronized
    fun append(chars: CharArray, count: Int) {
        var offset = 0
        if (head.length < headLimit) {
            val take = minOf(count, headLimit - head.length)
            head.append(chars, 0, take)
            offset = take
        }
        if (offset < count) {
            tail.append(chars, offset, count - offset)
            if (tail.length > tailLimit) {
                val excess = tail.length - tailLimit
                tail.delete(0, excess)
                dropped += excess
            }
        }
    }

    @Synchronized
    override fun toString(): String = if (dropped == 0L) {
        head.toString() + tail
    } else {
        "$head\n... [$dropped characters of output truncated] ...\n$tail"
    }
}
