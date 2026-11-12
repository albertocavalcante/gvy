// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider

@Isolated
class CliOutputLimitTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `stdout above its byte budget fails before output assertions`() {
        assertOutputLimit(STDOUT_BYTES, 0, "stdout")
    }

    @Test
    fun `stderr above its byte budget fails before output assertions`() {
        assertOutputLimit(0, STDOUT_BYTES, "stderr")
    }

    @Test
    fun `simultaneous stdout and stderr are drained without pipe deadlock`() {
        assertOutputLimit(DUAL_STREAM_BYTES, DUAL_STREAM_BYTES, null)
    }

    @Test
    fun `a real command completes both large streams without truncation or pipe deadlock`() {
        compileProducer()
        val workers = ConcurrentLinkedQueue<Thread>()
        val factory = ThreadFactory { task ->
            Thread(task, "gvy-cli-output-control").apply {
                isDaemon = true
                workers.add(this)
            }
        }
        var streamsClosed = false
        lateinit var process: Process
        SessionResources().use { resources ->
            val capture = resources.own(CliOutputCapture(REAL_SUCCESS_LIMIT_BYTES, threadFactory = factory))
            val command = commandStep(REAL_SUCCESS_BYTES, REAL_SUCCESS_BYTES)
            process = ProcessBuilder(listOf(command.command) + command.args).directory(workspace.toFile()).start()
            resources.own(
                AutoCloseable {
                    closeProducer(process)
                    streamsClosed = true
                },
            )

            val output = capture.await(process, COMMAND_TIMEOUT_SECONDS)
            val expected = "x".repeat(REAL_SUCCESS_BYTES)
            assertThat(output.stdout).isEqualTo(expected)
            assertThat(output.stderr).isEqualTo(expected)
        }
        assertThat(process.isAlive).isFalse()
        assertThat(streamsClosed).isTrue()
        assertThat(workers).isNotEmpty().allMatch { !it.isAlive }
    }

    private fun closeProducer(process: Process) {
        AutoCloseable {
            process.inputStream.use { process.errorStream.use { process.outputStream.close() } }
        }.use {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(PRODUCER_CLEANUP_SECONDS, TimeUnit.SECONDS)) { "Output producer did not exit" }
            }
        }
    }

    private fun assertOutputLimit(stdoutBytes: Int, stderrBytes: Int, channel: String?) {
        compileProducer()
        val context = context()
        lateinit var process: Process
        val executor = CliCommandStepExecutor { builder -> builder.start().also { process = it } }
        val previous = System.getProperty(OUTPUT_LIMIT_PROPERTY)
        try {
            System.setProperty(OUTPUT_LIMIT_PROPERTY, TEST_LIMIT_BYTES.toString())

            val assertion = assertThatThrownBy {
                executor.execute(
                    commandStep(stdoutBytes, stderrBytes),
                    context,
                )
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("byte limit")
                .hasMessageContaining(TEST_LIMIT_BYTES.toString())
                .hasMessageContaining("sample")
                .hasMessageContaining("cleanup confirmed")
            channel?.let { assertion.hasMessageContaining(it) }
            assertThat(context.lastResult).isNull()
            assertThat(process.isAlive).isFalse()
        } finally {
            if (previous == null) {
                System.clearProperty(OUTPUT_LIMIT_PROPERTY)
            } else {
                System.setProperty(OUTPUT_LIMIT_PROPERTY, previous)
            }
        }
    }

    private fun commandStep(stdoutBytes: Int, stderrBytes: Int) = ScenarioStep.CliCommand(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        args = listOf(
            "-Xmx64m",
            "-cp",
            workspace.toString(),
            "CliOutputProducer",
            stdoutBytes.toString(),
            stderrBytes.toString(),
        ),
        timeoutSeconds = COMMAND_TIMEOUT_SECONDS,
    )

    private fun compileProducer() {
        val source = workspace.resolve("CliOutputProducer.java")
        Files.writeString(
            source,
            """
            public class CliOutputProducer {
                public static void main(String[] args) throws Exception {
                    int stdout = Integer.parseInt(args[0]);
                    int stderr = Integer.parseInt(args[1]);
                    Thread first = new Thread(() -> write(System.out, stdout));
                    Thread second = new Thread(() -> write(System.err, stderr));
                    first.start();
                    second.start();
                    first.join();
                    second.join();
                }
                private static void write(java.io.PrintStream stream, int count) {
                    byte[] buffer = new byte[8192];
                    java.util.Arrays.fill(buffer, (byte) 'x');
                    while (count > 0) {
                        int size = Math.min(buffer.length, count);
                        stream.write(buffer, 0, size);
                        count -= size;
                    }
                    stream.flush();
                }
            }
            """.trimIndent(),
        )
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "CLI control requires a JDK compiler" }
        assertThat(compiler.run(null, null, null, source.toString())).isZero()
    }

    private fun context() = ScenarioContext(
        definition = ScenarioDefinition(Scenario("cli-output-limit", steps = emptyList()), "scenario.yaml"),
        session = mockk(),
        workspace = ScenarioWorkspace(workspace),
        jsonPathConfig = Configuration.defaultConfiguration(),
    )

    private companion object {
        const val OUTPUT_LIMIT_PROPERTY = "groovy.lsp.e2e.cliOutputLimitBytes"
        const val TEST_LIMIT_BYTES = 16
        const val STDOUT_BYTES = 32
        const val DUAL_STREAM_BYTES = 65_536
        const val REAL_SUCCESS_BYTES = 262_144
        const val REAL_SUCCESS_LIMIT_BYTES = 524_288
        const val COMMAND_TIMEOUT_SECONDS = 30L
        const val PRODUCER_CLEANUP_SECONDS = 5L
    }
}
