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
 */
internal object BoundedProcessRunner {

    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean)

    private val READER_JOIN_TIMEOUT = 5.seconds

    fun run(command: List<String>, directory: Path, timeout: Duration): Result {
        val process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()

        val output = StringBuffer()
        val reader = Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') } }
        }, "bounded-process-reader").apply { isDaemon = true }
        reader.start()

        try {
            val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!finished) {
                killTree(process)
                reader.join(READER_JOIN_TIMEOUT.toJavaDuration().toMillis())
                return Result(null, output.toString(), timedOut = true)
            }
            reader.join(READER_JOIN_TIMEOUT.toJavaDuration().toMillis())
            return Result(process.exitValue(), output.toString(), timedOut = false)
        } catch (e: InterruptedException) {
            killTree(process)
            throw e
        }
    }

    private fun killTree(process: Process) {
        // Snapshot descendants before killing the parent, which would reparent them.
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
