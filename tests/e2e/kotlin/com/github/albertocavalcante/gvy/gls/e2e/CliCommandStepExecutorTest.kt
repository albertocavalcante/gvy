package com.github.albertocavalcante.gvy.gls.e2e

import com.jayway.jsonpath.Configuration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.jar.JarFile

class CliCommandStepExecutorTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `successful CLI output is saved before its files and streams are released`() {
        val process = ControlledProcess()
        val invocation = Invocation(process)
        val context = context()

        invocation.executor.execute(ScenarioStep.CliCommand("tool", saveAs = "cli"), context)

        assertThat(context.savedResults["cli"].toString()).contains("hello", "stderr")
        invocation.assertReleased()
        assertThat(process.destroyed).isFalse()
    }

    @Test
    fun `an unexpected exit still releases CLI files and streams`() {
        val invocation = Invocation(ControlledProcess(exitCode = 7))

        assertThatThrownBy { invocation.executor.execute(ScenarioStep.CliCommand("tool"), context()) }
            .isInstanceOf(AssertionError::class.java).hasMessageContaining("exit code 7")

        invocation.assertReleased()
    }

    @Test
    fun `a timed out CLI is terminated and awaited before its outputs are removed`() {
        val process = ControlledProcess(completed = false)
        val invocation = Invocation(process)

        assertThatThrownBy { invocation.executor.execute(ScenarioStep.CliCommand("tool"), context()) }
            .isInstanceOf(TimeoutException::class.java)

        invocation.assertReleased()
        assertThat(process.destroyed).isTrue()
        assertThat(process.waitCalls).isEqualTo(2)
    }

    @Test
    fun `an interrupted CLI wait still terminates the child and removes outputs`() {
        val process = ControlledProcess(completed = false, firstWaitFailure = InterruptedException("cancelled"))
        val invocation = Invocation(process)

        assertThatThrownBy { invocation.executor.execute(ScenarioStep.CliCommand("tool"), context()) }
            .isInstanceOf(InterruptedException::class.java)

        invocation.assertReleased()
        assertThat(process.destroyed).isTrue()
    }

    @Test
    fun `a timeout stays primary when termination and stream cleanup both fail`() {
        val terminationFailure = IOException("termination wait failed")
        val streamFailure = IOException("stdin close failed")
        val process = ControlledProcess(
            completed = false,
            terminationFailure = terminationFailure,
            streamFailure = streamFailure,
        )
        val invocation = Invocation(process)

        val observed = checkNotNull(
            catchThrowable {
                invocation.executor.execute(ScenarioStep.CliCommand("tool"), context())
            },
        )

        assertThat(observed).isInstanceOf(TimeoutException::class.java)
        assertThat(observed.suppressed).containsExactly(terminationFailure)
        assertThat(terminationFailure.suppressed).containsExactly(streamFailure)
        invocation.assertReleased()
    }

    @Test
    fun `a failed CLI launch removes files created before starting the child`() {
        lateinit var stdout: Path
        lateinit var stderr: Path
        val failure = IOException("cannot start CLI")
        val executor = CliCommandStepExecutor { builder ->
            stdout = builder.redirectOutput().file().toPath()
            stderr = builder.redirectError().file().toPath()
            throw failure
        }

        assertThatThrownBy { executor.execute(ScenarioStep.CliCommand("tool"), context()) }.isSameAs(failure)

        assertThat(stdout).doesNotExist()
        assertThat(stderr).doesNotExist()
    }

    @Test
    fun `a CLI returned after cancellation is closed before any command wait begins`() {
        val process = ControlledProcess(completed = false)
        val invocation = Invocation(process)

        assertThatThrownBy {
            invocation.executor.execute(
                ScenarioStep.CliCommand("tool"),
                context(checkActive = { throw InterruptedException("cancelled during launch") }),
            )
        }.isInstanceOf(InterruptedException::class.java)

        invocation.assertReleased()
        assertThat(process.destroyed).isTrue()
        assertThat(process.waitCalls).isEqualTo(1) // Only the termination wait runs.
    }

    @Test
    fun `a failure reading CLI output still releases files and streams`() {
        val invocation = Invocation(ControlledProcess(), unreadableOutput = true)

        assertThatThrownBy { invocation.executor.execute(ScenarioStep.CliCommand("tool"), context()) }
            .isInstanceOf(IOException::class.java)

        invocation.assertReleased()
    }

    @Test
    fun `a real CLI archive sees only authored workspace files during execution`() {
        val input = Files.createDirectory(workspace.resolve("authored"))
        Files.writeString(input.resolve("authored.txt"), "authored content")
        val archive = workspace.resolve("export.jar")
        val jar = Path.of(System.getProperty("java.home"), "bin", "jar").toString()

        CliCommandStepExecutor().execute(
            ScenarioStep.CliCommand(
                jar,
                args = listOf("-J-Xmx32m", "--create", "--file", archive.toString(), "--no-manifest", "."),
            ),
            context(input),
        )

        JarFile(archive.toFile()).use { exported ->
            assertThat(exported.entries().asSequence().map { it.name }.toList()).containsExactly("authored.txt")
        }
    }

    private fun context(root: Path = workspace, checkActive: () -> Unit = {}) = ScenarioContext(
        definition = ScenarioDefinition(Scenario("cli-resources", steps = emptyList()), "scenario.yaml"),
        sessionAccess = ScenarioSessionAccess({ mockk() }, checkActive),
        workspace = ScenarioWorkspace(root),
        jsonPathConfig = Configuration.defaultConfiguration(),
    )

    private class Invocation(private val process: ControlledProcess, unreadableOutput: Boolean = false) {
        private lateinit var stdout: Path
        private lateinit var stderr: Path
        val executor = CliCommandStepExecutor { builder ->
            stdout = builder.redirectOutput().file().toPath()
            stderr = builder.redirectError().file().toPath()
            if (unreadableOutput) {
                Files.deleteIfExists(stdout)
                Files.createDirectory(stdout)
            } else {
                Files.writeString(stdout, "hello")
            }
            Files.writeString(stderr, "stderr")
            process
        }

        fun assertReleased() {
            assertThat(stdout).doesNotExist()
            assertThat(stderr).doesNotExist()
            assertThat(process.streamsClosed).isEqualTo(PROCESS_STREAM_COUNT)
            assertThat(process.isAlive).isFalse()
        }
    }

    private class ControlledProcess(
        private val exitCode: Int = 0,
        private val completed: Boolean = true,
        private val firstWaitFailure: Exception? = null,
        private val terminationFailure: IOException? = null,
        private val streamFailure: IOException? = null,
    ) : Process() {
        var streamsClosed = 0
        var destroyed = false
        var waitCalls = 0
        private var alive = !completed
        private val stdout = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                streamsClosed++
            }
        }
        private val stderr = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                streamsClosed++
            }
        }
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() {
                streamsClosed++
                streamFailure?.let { throw it }
            }
        }

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = stdin
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls++
            if (waitCalls == 1 && firstWaitFailure != null) throw firstWaitFailure
            if (waitCalls > 1 && terminationFailure != null) throw terminationFailure
            return !alive
        }
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            destroyed = true
            alive = false
        }
        override fun destroyForcibly(): Process = apply { destroy() }
        override fun isAlive(): Boolean = alive
    }

    private companion object {
        const val PROCESS_STREAM_COUNT = 3
    }
}
