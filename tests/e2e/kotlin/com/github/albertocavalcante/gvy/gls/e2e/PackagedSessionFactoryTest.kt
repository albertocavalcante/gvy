// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.albertocavalcante.gvy.gls.testing.client.HarnessLanguageClient
import com.github.albertocavalcante.gvy.jenkins.stubs.JenkinsStubsLocation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PackagedSessionFactoryTest {
    private val originalProperties = mutableMapOf<String, String?>()

    @BeforeEach
    fun installFakeLegacySelector() {
        mapOf(
            "groovy.lsp.e2e.serverClasspath" to "fake-classpath",
            "groovy.lsp.e2e.mainClass" to "example.FakeMain",
        ).forEach { (key, value) ->
            originalProperties[key] = System.getProperty(key)
            System.setProperty(key, value)
        }
    }

    @AfterEach
    fun restoreLegacySelector() {
        originalProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `packaged launch chooses its verified staging copy and explicit heap before stdio`() {
        val artifact = artifact()
        val launch = launch(artifact)
        var command: List<String> = emptyList()
        var environment: Map<String, String> = emptyMap()
        var stagedJar: Path? = null
        var stagedExistedAtStart = false
        val stop = IOException("secret-canary")
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                command = builder.command().toList()
                environment = builder.environment().toMap()
                val jarIndex = command.indexOf("-jar")
                stagedJar = command.getOrNull(jarIndex + 1)?.takeIf { jarIndex >= 0 }?.let { Path.of(it) }
                stagedExistedAtStart = stagedJar?.let { Files.exists(it) } == true
                throw stop
            },
            packagedLaunch = launch,
            evidencePolicy = SessionEvidencePolicy.Private,
        )

        val failure = assertThrows<PackagedLaunchException> {
            factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        }
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ProcessStartFailed)
        assertThat(failure.message).doesNotContain("secret-canary")
        assertThat(failure.cause).isNull()
        assertThat(stagedExistedAtStart).isTrue()
        val jarIndex = command.indexOf("-jar")
        assertThat(jarIndex).isGreaterThanOrEqualTo(3)
        assertThat(command.take(3)).containsExactly(launch.javaExecutable.toString(), "-Xms64m", "-Xmx256m")
        assertThat(command.drop(jarIndex)).containsExactly("-jar", stagedJar.toString(), "stdio")
        assertThat(stagedJar).isNotEqualTo(artifact)
        assertThat(checkNotNull(stagedJar).parent).doesNotExist()
        assertThat(reservedNames(environment)).isEmpty()
    }

    @Test
    fun `in process rejects packaged override before starting process or server`() {
        var started = false
        val factory = LanguageServerSessionFactory(
            startProcess = {
                started = true
                throw IOException("unexpected")
            },
            createServer = {
                started = true
                throw IOException("unexpected")
            },
            packagedLaunch = launch(artifact()),
        )
        assertThatThrownBy { factory.start(ServerConfig(mode = ServerLaunchMode.InProcess), "private") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(started).isFalse()
    }

    @Test
    fun `reject policy starts process when all authored JVM options are empty`() {
        val names = JVM_OPTION_ALIASES
        val environment = names.associateWith { "" } + ("GRADLE_USER_HOME" to "isolated")
        var observed: Map<String, String>? = null
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                observed = builder.environment().toMap()
                throw IOException("secret-canary")
            },
            packagedLaunch = launch(artifact()).copy(javaEnvironmentPolicy = JavaEnvironmentPolicy.Reject),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        val failure = assertThrows<PackagedLaunchException> {
            factory.start(ServerConfig(env = environment), "private")
        }
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ProcessStartFailed)
        assertThat(failure.message).doesNotContain("secret-canary")
        assertThat(failure.cause).isNull()
        assertThat(observed).isNotNull()
        assertThat(reservedNames(checkNotNull(observed))).isEmpty()
    }

    @Test
    fun `private stderr is drained without exposing bytes or launch secrets`() {
        val bytes = ("secret-canary" + "x".repeat(128 * 1024)).toByteArray()
        val process = ControlledProcess(ByteArrayInputStream(bytes))
        var stagedJar: Path? = null
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            val session = LanguageServerSessionFactory(
                startProcess = { builder ->
                    val command = builder.command()
                    val jarIndex = command.indexOf("-jar")
                    stagedJar = if (jarIndex >= 0) command.getOrNull(jarIndex + 1)?.let { Path.of(it) } else null
                    process
                },
                packagedLaunch = launch(artifact()),
                evidencePolicy = SessionEvidencePolicy.Private,
            ).start(
                ServerConfig(args = listOf("--token=secret-canary"), env = mapOf("GRADLE_USER_HOME" to "isolated")),
                "secret-canary",
            )
            try {
                val evidence = session.runtimeEvidence
                assertThat(evidence).isNotNull()
                assertThat(evidence?.before?.artifact).isEqualTo(VerificationState.Verified)
                assertThat(evidence?.afterStart?.artifact).isEqualTo(VerificationState.Verified)
                assertThat(evidence?.requestedJvmSettings).isEqualTo(JvmSettings(256, 64))
                assertThat(evidence?.javaRuntimeVersion).isNull()
                assertThat(evidence?.effectiveMaxHeapBytes).isNull()
                assertThat(stagedJar).isNotNull()
                assertThat(stagedJar?.let { Files.exists(it) }).isTrue()
                assertThat(process.awaitStderr()).isTrue()
            } finally {
                session.close()
            }
            assertThat(stagedJar?.let { Files.exists(it) }).isFalse()
            val stderr = checkNotNull(session.snapshotStderrEvidence())
            assertThat(stderr.bytesObserved).isEqualTo(bytes.size.toLong())
            assertThat(stderr.eof).isTrue()
            assertThat(stderr.failed).isFalse()
            assertThat(appender.list.joinToString("\n") { it.formattedMessage }).doesNotContain("secret-canary")
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `private stderr read failure records observed bytes without error text`() {
        val process = ControlledProcess(object : InputStream() {
            private var remaining = 7
            override fun read(): Int {
                if (remaining-- > 0) return 'x'.code
                throw IOException("secret-canary")
            }
        })
        val session = LanguageServerSessionFactory(
            startProcess = { process },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        ).start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        try {
            assertThat(process.awaitStderr()).isTrue()
        } finally {
            session.close()
        }
        val stderr = checkNotNull(session.snapshotStderrEvidence())
        assertThat(stderr.bytesObserved).isEqualTo(7)
        assertThat(stderr.eof).isFalse()
        assertThat(stderr.failed).isTrue()
        assertThat(stderr.toString()).doesNotContain("secret-canary")
    }

    @Test
    fun `private stderr fatal read is replayed locally after owned cleanup`() {
        assertPrivateStderrControl(AssertionError("secret-reader-canary"))
    }

    @Test
    fun `private stderr fatal close after EOF records failure and is replayed locally`() {
        assertPrivateStderrControl(AssertionError("secret-close-canary"), failOnClose = true)
    }

    @Test
    fun `private stderr interruption is replayed after cleanup and restores caller interrupt`() {
        assertPrivateStderrControl(InterruptedException("local reader interruption"))
    }

    @Test
    fun `private stderr cancellation is replayed after cleanup`() {
        assertPrivateStderrControl(CancellationException("local reader cancellation"))
    }

    @Test
    fun `private stderr preserves suppressed fatal and interruption after owned cleanup`() {
        val fatal = AssertionError("local reader fatal")
        val failure = IOException("private wrapper").apply {
            addSuppressed(fatal)
            addSuppressed(InterruptedException("local reader interruption"))
        }
        assertPrivateStderrControl(failure, expectedControl = fatal, expectedInterrupt = true)
    }

    @Test
    fun `private launch sanitizes throwing cause accessors and cleans staging`() {
        var staged: Path? = null
        val wrapper = object : IOException("private wrapper") {
            override val cause: Throwable
                get() = throw IllegalStateException("secret-cause-canary")
        }
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                staged = Path.of(command[command.indexOf("-jar") + 1])
                throw wrapper
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        val failure = assertThrows<PackagedLaunchException>("throwing launch cause accessor must remain private") {
            factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        }
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ProcessStartFailed)
        assertThat(failure.stackTraceToString()).doesNotContain("secret-cause-canary", "private wrapper")
        assertThat(staged?.parent).doesNotExist()
    }

    @Test
    fun `private control only interruption remains visible after fatal promotion`() {
        val interruption = InterruptedException("local interruption")
        val fatal = AssertionError("local fatal")
        interruption.addSuppressed(fatal)
        var otherResourceClosed = false
        val resources = SessionResources().apply {
            own(AutoCloseable { throw interruption })
            own(AutoCloseable { otherResourceClosed = true })
        }
        try {
            val caught = try {
                privateSession(resources).close()
                null
            } catch (failure: Throwable) {
                failure
            }
            assertThat(caught === fatal).isTrue()
            assertThat(fatal.suppressed.any { it === interruption })
                .describedAs("fatal promotion must retain the observed interruption")
                .isTrue()
            assertThat(otherResourceClosed).isTrue()
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `fatal cleanup never reads suppressed failure messages`() {
        val fatal = AssertionError("local fatal")
        fatal.addSuppressed(object : IOException() {
            override val message: String
                get() = throw IllegalStateException("secret-message-canary")
        })
        val resources = SessionResources().apply { own(AutoCloseable { throw fatal }) }
        val caught = try {
            privateSession(resources).close()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertThat(caught === fatal).describedAs("fatal cleanup must not read suppressed messages").isTrue()
    }

    @Test
    fun `explicit packaged launch ignores malformed unused legacy jar selector`() {
        val key = "groovy.lsp.e2e.execJar"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "secret-legacy-canary\u0000.jar")
            SessionEvidencePolicy.entries.forEach { policy ->
                var started = false
                val caught = try {
                    LanguageServerSessionFactory(
                        startProcess = {
                            started = true
                            throw IOException("private launch failure")
                        },
                        packagedLaunch = launch(artifact()),
                        evidencePolicy = policy,
                    ).start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private").close()
                    null
                } catch (failure: Throwable) {
                    failure
                }
                // Keep the deliberately malformed path out of an assertion failure's exception graph.
                val failure = when (caught) {
                    is PackagedLaunchException -> caught
                    is InvalidPathException -> throw AssertionError(
                        "explicit launch must ignore unused legacy jar: InvalidPathException",
                    )
                    else -> throw AssertionError("Unexpected explicit launch outcome")
                }
                assertThat(started).isTrue()
                assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ProcessStartFailed)
                assertThat(failure.stackTraceToString()).doesNotContain("secret-legacy-canary")
            }
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test
    fun `remove policy strips each authored JVM options variable`() {
        val names = JVM_OPTION_ALIASES
        val authored = names.associateWith { "-Dsecret=canary" } + ("GRADLE_USER_HOME" to "isolated")
        var observed: Map<String, String> = emptyMap()
        val factory = LanguageServerSessionFactory(
            startProcess = {
                observed = it.environment().toMap()
                throw IOException("secret-canary")
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        assertThrows<PackagedLaunchException> { factory.start(ServerConfig(env = authored), "private") }
        assertThat(reservedNames(observed)).isEmpty()
    }

    @Test
    fun `reject policy blocks each authored JVM options variable`() {
        val artifact = artifact()
        val names = JVM_OPTION_ALIASES
        names.forEach { name ->
            val environment = names.associateWith { "" } +
                mapOf(name to "secret-canary", "GRADLE_USER_HOME" to "isolated")
            var started = false
            val factory = LanguageServerSessionFactory(
                startProcess = {
                    started = true
                    throw IOException("unexpected")
                },
                packagedLaunch = launch(artifact).copy(javaEnvironmentPolicy = JavaEnvironmentPolicy.Reject),
                evidencePolicy = SessionEvidencePolicy.Private,
            )
            val failure = assertThrows<PackagedLaunchException> {
                factory.start(
                    ServerConfig(env = environment),
                    "private",
                )
            }
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.EnvironmentRejected)
            assertThat(started).isFalse()
        }
    }

    @Test
    fun `default launch preserves original process start exception`() {
        val failure = IOException("legacy-canary")
        val factory = LanguageServerSessionFactory(startProcess = { throw failure })
        assertThatThrownBy {
            factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "legacy")
        }.isSameAs(failure)
    }

    @Test
    fun `artifact mismatch is rejected before process acquisition`() {
        var started = false
        val factory = LanguageServerSessionFactory(
            startProcess = {
                started = true
                throw IOException("unexpected")
            },
            packagedLaunch = launch(artifact()).copy(expectedSha256 = "f".repeat(64)),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        val failure = assertThrows<PackagedLaunchException> {
            factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        }
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactDigestMismatch)
        assertThat(started).isFalse()
    }

    @Test
    fun `post start staged tampering destroys acquired process and cleans the stage`() {
        val process = ControlledProcess(ByteArrayInputStream(byteArrayOf()))
        lateinit var staged: Path
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                staged = Path.of(command[command.indexOf("-jar") + 1])
                Files.writeString(staged, "changed after start")
                process
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        val failure = assertThrows<PackagedLaunchException> {
            factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        }
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ArtifactDigestMismatch)
        assertThat(failure.verification.source).isEqualTo(VerificationState.NotChecked)
        assertThat(failure.verification.observedSource).isNull()
        assertThat(process.isAlive).isFalse()
        assertThat(staged.parent).doesNotExist()
    }

    @Test
    fun `private process requires explicit nonblank Gradle home before acquisition`() {
        listOf(emptyMap(), mapOf("GRADLE_USER_HOME" to "  ")).forEach { environment ->
            var started = false
            val factory = LanguageServerSessionFactory(
                startProcess = {
                    started = true
                    throw IOException("secret-canary")
                },
                evidencePolicy = SessionEvidencePolicy.Private,
            )
            val failure = assertThrows<PackagedLaunchException> {
                factory.start(ServerConfig(env = environment), "secret-canary")
            }
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidConfiguration)
            assertThat(failure.message).doesNotContain("secret-canary")
            assertThat(failure.cause).isNull()
            assertThat(started).isFalse()
        }
    }

    @Test
    fun `private unsupported modes are rejected before acquisition`() {
        listOf(ServerLaunchMode.InProcess, ServerLaunchMode.Socket).forEach { mode ->
            var started = false
            val factory = LanguageServerSessionFactory(
                startProcess = {
                    started = true
                    throw IOException("secret-canary")
                },
                createServer = {
                    started = true
                    throw IOException("secret-canary")
                },
                evidencePolicy = SessionEvidencePolicy.Private,
            )
            val failure = assertThrows<PackagedLaunchException> {
                factory.start(
                    ServerConfig(mode = mode, env = mapOf("GRADLE_USER_HOME" to "isolated")),
                    "secret-canary",
                )
            }
            assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.InvalidConfiguration)
            assertThat(failure.message).doesNotContain("secret-canary")
            assertThat(failure.cause).isNull()
            assertThat(started).isFalse()
        }
    }

    @Test
    fun `post start mismatch retains safe primary and safe cleanup suppression`() {
        val process = ControlledProcess(ByteArrayInputStream(byteArrayOf()), onDestroy = {
            throw IOException("secret-cleanup-canary")
        })
        val result = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        var staged: Path? = null
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                val path = Path.of(command[command.indexOf("-jar") + 1])
                staged = path
                Files.writeString(path, "changed after start")
                process
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        val worker = Thread({
            try {
                factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private").close()
            } catch (failure: Exception) {
                result.set(failure)
            } finally {
                finished.countDown()
            }
        }, "packaged-test-failing-acquisition").apply { isDaemon = true }
        try {
            worker.start()
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
            val failure = result.get()
            assertThat(failure).isInstanceOf(PackagedLaunchException::class.java)
            assertThat(
                (failure as PackagedLaunchException).reason,
            ).isEqualTo(PackagedLaunchFailure.ArtifactDigestMismatch)
            assertThat(failure.cause).isNull()
            assertThat(failure.suppressed).hasSize(1)
            assertThat(failure.suppressed.single().message).isEqualTo("Session cleanup failed")
            assertThat(failure.stackTraceToString()).doesNotContain("secret-cleanup-canary")
            assertThat(process.isAlive).isFalse()
            assertThat(staged?.parent?.let { Files.exists(it) }).isFalse()
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
        assertThat(worker.isAlive).isFalse()
    }

    @Test
    fun `private close sanitizes cleanup failure and restores interruption`() {
        val process = ControlledProcess(ByteArrayInputStream(byteArrayOf()), onDestroy = {
            throw IOException("secret-cleanup-canary")
        })
        var staged: Path? = null
        val session = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                staged = Path.of(command[command.indexOf("-jar") + 1])
                process
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        ).start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        val result = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean()
        val finished = CountDownLatch(1)
        val worker = Thread({
            Thread.currentThread().interrupt()
            try {
                session.close()
            } catch (failure: Exception) {
                result.set(failure)
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted)
                finished.countDown()
            }
        }, "packaged-test-failing-close").apply { isDaemon = true }
        try {
            worker.start()
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(result.get()).isInstanceOf(IllegalStateException::class.java)
            assertThat(result.get()?.message).isEqualTo("Session cleanup failed")
            assertThat(result.get()?.cause).isNull()
            assertThat(result.get()?.stackTraceToString()).doesNotContain("secret-cleanup-canary")
            assertThat(interrupted.get()).isTrue()
            assertThat(process.isAlive).isFalse()
            assertThat(staged?.parent?.let { Files.exists(it) }).isFalse()
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
        assertThat(worker.isAlive).isFalse()
    }

    @Test
    fun `private stderr drains a bounded pipe and teardown retires its reader`() {
        val reader = PipedInputStream(8192)
        val writer = PipedOutputStream(reader)
        val written = CountDownLatch(1)
        val writerFailure = AtomicReference<Throwable?>()
        val releaseWriter = CountDownLatch(1)
        val process = ControlledProcess(reader, onDestroy = {
            writer.close()
            releaseWriter.countDown()
        })
        var staged: Path? = null
        var session: LanguageServerSession? = null
        val oldThreads = Thread.getAllStackTraces().keys
        val closed = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val closeThread = Thread({
            try {
                session?.close()
            } catch (failure: Exception) {
                closeFailure.set(failure)
            } finally {
                closed.countDown()
            }
        }, "packaged-test-session-close").apply { isDaemon = true }
        val writerThread = Thread({
            try {
                writer.write(ByteArray(128 * 1024) { 'x'.code.toByte() })
                written.countDown()
                releaseWriter.await()
            } catch (failure: Exception) {
                writerFailure.set(failure)
                if (failure is InterruptedException) Thread.currentThread().interrupt()
            } finally {
                written.countDown()
            }
        }, "packaged-test-stderr-writer").apply { isDaemon = true }
        try {
            session = LanguageServerSessionFactory(
                startProcess = { builder ->
                    val command = builder.command()
                    staged = Path.of(command[command.indexOf("-jar") + 1])
                    process
                },
                packagedLaunch = launch(artifact()),
                evidencePolicy = SessionEvidencePolicy.Private,
            ).start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
            writerThread.start()
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(writerFailure.get()).isNull()
            assertThat(writerThread.isAlive).isTrue()
            val pump = Thread.getAllStackTraces().keys.single {
                it !in oldThreads && it.name == "groovy-lsp-e2e-private-stderr"
            }
            assertThat(pump.isAlive).isTrue()
            closeThread.start()
            assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(closeFailure.get()).isNull()
            pump.join(5_000)
            assertThat(pump.isAlive).isFalse()
            val stderr = checkNotNull(session.snapshotStderrEvidence())
            assertThat(stderr.bytesObserved).isEqualTo(128L * 1024)
            assertThat(stderr.eof).isTrue()
            assertThat(stderr.failed).isFalse()
            assertThat(staged?.parent?.let { Files.exists(it) }).isFalse()
        } finally {
            releaseWriter.countDown()
            writer.close()
            reader.close()
            if (session != null && closeThread.state == Thread.State.NEW) closeThread.start()
            closeThread.join(5_000)
            writerThread.join(5_000)
        }
        assertThat(closeThread.isAlive).isFalse()
        assertThat(writerThread.isAlive).isFalse()
    }

    @Test
    fun `default session reports stderr observation as unavailable`() {
        val process = ControlledProcess(ByteArrayInputStream(byteArrayOf()))
        val session = LanguageServerSessionFactory(startProcess = { process })
            .start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "legacy")
        try {
            assertThat(session.snapshotStderrEvidence()).isNull()
        } finally {
            session.close()
        }
    }

    @Test
    fun `private launch propagates cancellation and cleans its staging`() {
        val control = CancellationException("local cancellation")
        var staged: Path? = null
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                staged = Path.of(command[command.indexOf("-jar") + 1])
                throw control
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        assertThatThrownBy {
            factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        }.isSameAs(control)
        assertThat(staged?.parent?.let { Files.exists(it) }).isFalse()
    }

    @Test
    fun `private launch preserves interruption and restores flag after staging cleanup`() {
        val control = InterruptedException("local interruption")
        var staged: Path? = null
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                staged = Path.of(command[command.indexOf("-jar") + 1])
                throw control
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        try {
            assertThatThrownBy {
                factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
            }.isSameAs(control)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(staged?.parent?.let { Files.exists(it) }).isFalse()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `private launch preserves closed by interrupt and restores flag`() {
        val control = ClosedByInterruptException()
        var staged: Path? = null
        val factory = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                staged = Path.of(command[command.indexOf("-jar") + 1])
                throw control
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        try {
            assertThatThrownBy {
                factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
            }.isSameAs(control)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
            assertThat(staged?.parent?.let { Files.exists(it) }).isFalse()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `private launch sanitizes socket timeout without marking interrupt`() {
        val factory = LanguageServerSessionFactory(
            startProcess = { throw SocketTimeoutException("secret-canary") },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        )
        val failure = assertThrows<PackagedLaunchException> {
            factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        }
        assertThat(failure.reason).isEqualTo(PackagedLaunchFailure.ProcessStartFailed)
        assertThat(failure.message).doesNotContain("secret-canary")
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `packaged launch forwards the isolated Jenkins cache root before the jar`() {
        val key = JenkinsStubsLocation.CACHE_ROOT_PROPERTY
        val previous = System.getProperty(key)
        val cache = tempDir.resolve("jenkins-cache").toAbsolutePath().toString()
        var command: List<String> = emptyList()
        try {
            System.setProperty(key, cache)
            val factory = LanguageServerSessionFactory(
                startProcess = { builder ->
                    command = builder.command().toList()
                    throw IOException("stop")
                },
                packagedLaunch = launch(artifact()),
                evidencePolicy = SessionEvidencePolicy.Private,
            )
            assertThrows<PackagedLaunchException> {
                factory.start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
            }
            val propertyIndex = command.indexOf("-D$key=$cache")
            assertThat(propertyIndex).isGreaterThan(0)
            assertThat(propertyIndex).isLessThan(command.indexOf("-jar"))
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test
    fun `private cleanup promotes suppressed cancellation after closing every resource`() {
        val cancellation = CancellationException("local control")
        val closed = mutableListOf<Int>()
        val resources = SessionResources().apply {
            own(
                AutoCloseable {
                    closed += 1
                    throw cancellation
                },
            )
            own(
                AutoCloseable {
                    closed += 2
                    throw IOException("local cleanup")
                },
            )
            own(AutoCloseable { closed += 3 })
        }
        assertThatThrownBy { privateSession(resources).close() }.isSameAs(cancellation)
        assertThat(closed).containsExactly(3, 2, 1)
        assertThat(cancellation.suppressed.map { it.message }).contains("Session cleanup failed")
    }

    @Test
    fun `private cleanup promotes suppressed interruption and restores interrupt`() {
        val interruption = InterruptedException("local control")
        val closed = mutableListOf<Int>()
        val resources = SessionResources().apply {
            own(
                AutoCloseable {
                    closed += 1
                    throw interruption
                },
            )
            own(
                AutoCloseable {
                    closed += 2
                    throw IOException("local cleanup")
                },
            )
        }
        try {
            assertThatThrownBy { privateSession(resources).close() }.isSameAs(interruption)
            assertThat(closed).containsExactly(2, 1)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `private cleanup preserves primary cancellation when later cleanup fails`() {
        val cancellation = CancellationException("local control")
        val closed = mutableListOf<Int>()
        val resources = SessionResources().apply {
            own(
                AutoCloseable {
                    closed += 1
                    throw IOException("local cleanup")
                },
            )
            own(
                AutoCloseable {
                    closed += 2
                    throw cancellation
                },
            )
        }
        assertThatThrownBy { privateSession(resources).close() }.isSameAs(cancellation)
        assertThat(closed).containsExactly(2, 1)
    }

    @Test
    fun `private cleanup promotes suppressed fatal failure and closes all resources`() {
        val fatal = AssertionError("local fatal")
        val closed = mutableListOf<Int>()
        val resources = SessionResources().apply {
            own(
                AutoCloseable {
                    closed += 1
                    throw fatal
                },
            )
            own(
                AutoCloseable {
                    closed += 2
                    throw IOException("local cleanup")
                },
            )
        }
        assertThatThrownBy { privateSession(resources).close() }.isSameAs(fatal)
        assertThat(closed).containsExactly(2, 1)
    }

    @Test
    fun `private cleanup retains interruption flag even when fatal error takes priority`() {
        val fatal = AssertionError("local fatal")
        val interruption = ClosedByInterruptException()
        val closed = mutableListOf<Int>()
        val resources = SessionResources().apply {
            own(
                AutoCloseable {
                    closed += 1
                    throw interruption
                },
            )
            own(
                AutoCloseable {
                    closed += 2
                    throw fatal
                },
            )
        }
        try {
            assertThatThrownBy { privateSession(resources).close() }.isSameAs(fatal)
            assertThat(closed).containsExactly(2, 1)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertPrivateStderrControl(
        control: Throwable,
        failOnClose: Boolean = false,
        expectedControl: Throwable = control,
        expectedInterrupt: Boolean = control is InterruptedException,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = AtomicReference<Thread>()
        val uncaught = AtomicReference<Throwable?>()
        val closes = AtomicInteger()
        val stderr = object : InputStream() {
            override fun read(): Int {
                reader.set(Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test stderr reader was not released" }
                if (failOnClose) return -1
                throw control
            }
            override fun close() {
                if (failOnClose && closes.incrementAndGet() == 1) throw control
            }
        }
        var staged: Path? = null
        val process = ControlledProcess(stderr)
        val session = LanguageServerSessionFactory(
            startProcess = { builder ->
                val command = builder.command()
                staged = Path.of(command[command.indexOf("-jar") + 1])
                process
            },
            packagedLaunch = launch(artifact()),
            evidencePolicy = SessionEvidencePolicy.Private,
        ).start(ServerConfig(env = mapOf("GRADLE_USER_HOME" to "isolated")), "private")
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val pump = checkNotNull(reader.get())
            pump.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> uncaught.set(failure) }
            release.countDown()
            pump.join(5_000)
            assertThat(pump.isAlive).isFalse()
            assertThat(uncaught.get()).describedAs("private stderr must not reach uncaught handler").isNull()
            val caught = try {
                session.close()
                null
            } catch (failure: Throwable) {
                failure
            }
            assertThat(caught === expectedControl)
                .describedAs("private stderr control identity must survive owned cleanup")
                .isTrue()
            assertThat(Thread.currentThread().isInterrupted).isEqualTo(expectedInterrupt)
            assertThat(process.isAlive).isFalse()
            assertThat(staged?.parent).doesNotExist()
            val evidence = checkNotNull(session.snapshotStderrEvidence())
            assertThat(evidence.failed).isTrue()
            assertThat(evidence.eof).isEqualTo(failOnClose)
            assertThat(evidence.toString()).doesNotContain("secret-reader-canary", "secret-close-canary")
        } finally {
            Thread.interrupted()
            release.countDown()
            reader.get()?.join(5_000)
            session.close()
        }
    }

    private fun privateSession(resources: SessionResources): LanguageServerSession = LanguageServerSession(
        process = ControlledProcess(ByteArrayInputStream(byteArrayOf())),
        server = SessionTestServer(),
        client = HarnessLanguageClient(),
        endpoint = RemoteEndpoint(
            {},
            object : Endpoint {
                override fun request(method: String, parameter: Any?): CompletableFuture<*> =
                    CompletableFuture.completedFuture(null)
                override fun notify(method: String, parameter: Any?) = Unit
            },
        ),
        resources = resources,
        privateCleanup = true,
    )

    private class ControlledProcess(stderr: InputStream, private val onDestroy: () -> Unit = {}) : Process() {
        private val stderrDone = CountDownLatch(1)
        private val observedStderr = object : InputStream() {
            override fun read(): Int = try {
                stderr.read().also { if (it < 0) stderrDone.countDown() }
            } catch (failure: IOException) {
                stderrDone.countDown()
                throw failure
            }
            override fun close() = stderr.close()
        }
        fun awaitStderr(): Boolean = stderrDone.await(5, TimeUnit.SECONDS)
        private val stdout = ByteArrayInputStream(byteArrayOf())
        private val stdin = ByteArrayOutputStream()

        @Volatile
        private var alive = true
        override fun getInputStream(): InputStream = stdout
        override fun getOutputStream(): OutputStream = stdin
        override fun getErrorStream(): InputStream = observedStderr
        override fun isAlive(): Boolean = alive
        override fun exitValue(): Int = 0
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun destroy() {
            alive = false
            onDestroy()
        }
    }

    private fun reservedNames(environment: Map<String, String>): List<String> = environment.keys.filter { key ->
        JVM_OPTION_ALIASES.any { alias -> alias.equals(key, ignoreCase = true) }
    }

    private fun launch(artifact: Path) = PackagedLaunch(
        Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        ),
        artifact,
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))
            .joinToString("") { "%02x".format(it) },
        SourceStamp(COMMIT),
        JvmSettings(maxHeapMiB = 256, initialHeapMiB = 64),
    )

    private fun artifact(): Path {
        val path = tempDir.resolve("server.jar")
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        JarOutputStream(Files.newOutputStream(path), manifest).use { jar ->
            jar.putNextEntry(JarEntry("version.properties"))
            jar.write("gitSha=$COMMIT\ngitDirty=false\ngitDirtyHash=\n".toByteArray())
            jar.closeEntry()
        }
        return path
    }

    private companion object {
        val JVM_OPTION_ALIASES = listOf(
            "JAVA_TOOL_OPTIONS", "java_tool_options", "Java_Tool_Options",
            "JDK_JAVA_OPTIONS", "jdk_java_options", "Jdk_Java_Options",
            "_JAVA_OPTIONS", "_java_options", "_Java_Options",
        )
        val COMMIT = "a".repeat(40)
    }
}
