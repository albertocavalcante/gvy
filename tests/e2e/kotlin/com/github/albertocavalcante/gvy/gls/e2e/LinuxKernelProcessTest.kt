// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@EnabledOnOs(OS.LINUX)
class LinuxKernelProcessTest {
    @TempDir
    lateinit var evidence: Path

    @Test
    fun `a dedicated JVM reaps its escaped child without inheriting pidfds or stopping outside processes`() {
        runProbe("owned")
    }

    @Test
    fun `native observer failure stays unconfirmed before a separately controlled probe cleanup`() {
        runProbe("fault")
    }

    private fun runProbe(mode: String) {
        val outside = ProcessBuilder(kernelFixtureCommand("outside", evidence)).start()
        try {
            awaitKernelFixtureFile(evidence.resolve("outside-ready"))
            val probe = ProcessBuilder(kernelFixtureCommand(mode, evidence)).apply {
                SUPERVISOR_OPTION_ENV.forEach(environment()::remove)
            }.start()
            try {
                assertThat(probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
                val output = probe.inputStream.readAllBytes().toString(Charsets.UTF_8)
                val errors = probe.errorStream.readAllBytes().toString(Charsets.UTF_8)
                assertThat(probe.exitValue()).withFailMessage(errors).isZero()
                assertThat(output).contains("noInheritedPidfds=true", "kernelOwnershipEmpty=true")
                if (mode == "fault") assertThat(output).contains("observerFailureUnconfirmed=true")
                assertThat(outside.isAlive).describedAs("The pre-existing outside-scope control must survive").isTrue()
            } finally {
                closeKernelFixtureProcess(probe)
            }
        } finally {
            closeKernelFixtureProcess(outside)
        }
    }
}

/** Actual kernel calls are restricted to this separate heap-bounded fixture JVM. */
internal object LinuxKernelFixtureMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val evidence = Path.of(args[1])
        when (val role = args[0]) {
            "outside" -> {
                Files.writeString(evidence.resolve("outside-ready"), "ready")
                CountDownLatch(1).await(FIXTURE_SELF_EXPIRY_SECONDS, TimeUnit.SECONDS)
            }
            "payload" -> payload(evidence)
            "child" -> child(evidence)
            else -> probe(role, evidence)
        }
    }

    private fun probe(mode: String, evidence: Path) {
        val actualCalls = JnaLinuxProcessCalls()
        val reader = LinuxOwnedChildReader()
        val controlledCalls = if (mode == "fault") {
            object : LinuxProcessCalls by actualCalls {
                override fun openPidfd(pid: Int): LinuxNativeCall = if (pid == currentPid) {
                    actualCalls.openPidfd(pid)
                } else {
                    LinuxNativeCall(-1, INVALID_ARGUMENT)
                }
            }
        } else {
            actualCalls
        }
        val tested = LinuxNativeChildKernel(controlledCalls, reader::read)
        tested.prepare()
        val fallback = LinuxChildOwnership(LinuxNativeChildKernel(actualCalls, reader::read))
        val payloadErrors = evidence.resolve("payload-stderr")
        val payload = ProcessBuilder(kernelFixtureCommand("payload", evidence))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(payloadErrors.toFile()).start()
        AutoCloseable { fallback.release() }.use {
            AutoCloseable { closeKernelFixtureProcess(payload) }.use {
                check(payload.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && payload.exitValue() == 0) {
                    Files.newInputStream(payloadErrors).use { errors ->
                        errors.readNBytes(FIXTURE_ERROR_SAMPLE_BYTES).toString(Charsets.UTF_8)
                    }
                }
                awaitKernelFixtureFile(evidence.resolve("child-identity"))
                releaseTestedScope(tested, mode)
            }
        }
        val identity = Files.readAllLines(evidence.resolve("child-identity"))
        val started = Instant.parse(identity[1])
        val remaining = ProcessHandle.of(identity[0].toLong()).filter {
            it.info().startInstant().orElse(null) == started
        }
        check(remaining.isEmpty || !remaining.get().isAlive)
        check(Files.readString(evidence.resolve("payload-pidfds")) == "none")
        println("noInheritedPidfds=true")
        println("kernelOwnershipEmpty=true")
    }

    private fun releaseTestedScope(kernel: LinuxNativeChildKernel, mode: String) {
        if (mode != "fault") {
            LinuxChildOwnership(kernel).release()
            return
        }
        var observed = false
        try {
            LinuxChildOwnership(kernel).release()
        } catch (failure: LinuxOwnershipFailure) {
            check(failure.message.orEmpty().contains("unconfirmed"))
            observed = true
        }
        check(observed) { "Observer failure must remain an unconfirmed result" }
        println("observerFailureUnconfirmed=true")
    }

    private fun payload(evidence: Path) {
        check(noInheritedPidfds()) { "A capability pidfd escaped into payload startup" }
        Files.writeString(evidence.resolve("payload-pidfds"), "none")
        ProcessBuilder(kernelFixtureCommand("child", evidence))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        awaitKernelFixtureFile(evidence.resolve("child-identity"))
    }

    private fun child(evidence: Path) {
        val pid = ProcessHandle.current().pid()
        val session = NativeLibrary.getInstance("c").getFunction("setsid").invokeInt(emptyArray<Any>())
        check(session.toLong() == pid) { "Fixture child could not create its own session" }
        val staged = evidence.resolve("child-identity.part")
        Files.writeString(staged, "$pid\n${ProcessHandle.current().info().startInstant().orElseThrow()}\n")
        Files.move(staged, evidence.resolve("child-identity"), StandardCopyOption.ATOMIC_MOVE)
        CountDownLatch(1).await(FIXTURE_SELF_EXPIRY_SECONDS, TimeUnit.SECONDS)
    }

    private fun noInheritedPidfds(): Boolean = Files.newDirectoryStream(Path.of("/proc/self/fd")).use { descriptors ->
        descriptors.none { descriptor ->
            try {
                Files.readSymbolicLink(descriptor).toString().contains("[pidfd]")
            } catch (_: NoSuchFileException) {
                false // A concurrently closed JVM descriptor does not represent an inherited capability.
            }
        }
    }
}

private fun kernelFixtureCommand(role: String, evidence: Path): List<String> {
    val classpath = listOf(LinuxKernelFixtureMain::class.java, Unit::class.java, Native::class.java)
        .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toAbsolutePath().toString() }
        .distinct().joinToString(java.io.File.pathSeparator)
    return listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx128m",
        "-cp",
        classpath,
        LinuxKernelFixtureMain::class.java.name,
        role,
        evidence.toString(),
    )
}

private fun awaitKernelFixtureFile(path: Path) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS)
    while (!Files.exists(path) && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(HANDSHAKE_POLL_MILLIS)
    check(Files.exists(path)) { "Kernel fixture handshake missing" }
}

private fun closeKernelFixtureProcess(process: Process) {
    AutoCloseable {
        process.inputStream.use { process.errorStream.use { process.outputStream.close() } }
    }.use {
        if (process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(FIXTURE_CLEANUP_SECONDS, TimeUnit.SECONDS)) { "Kernel fixture did not stop" }
        }
    }
}

private val SUPERVISOR_OPTION_ENV = listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
private const val PROBE_TIMEOUT_SECONDS = 30L
private const val FIXTURE_CLEANUP_SECONDS = 5L
private const val FIXTURE_SELF_EXPIRY_SECONDS = 45L
private const val HANDSHAKE_POLL_MILLIS = 1L
private const val INVALID_ARGUMENT = 22
private const val FIXTURE_ERROR_SAMPLE_BYTES = 4096
