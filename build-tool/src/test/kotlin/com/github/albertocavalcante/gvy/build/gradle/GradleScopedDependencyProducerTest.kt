// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.DependencyProvenance
import com.github.albertocavalcante.gvy.build.TargetDependencyEvidence
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildException
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.UnknownModelException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GradleScopedDependencyProducerTest {
    @TempDir
    lateinit var temporary: Path

    private val directory = Path.of("transport-project").toAbsolutePath()
    private val provenance = DependencyProvenance("caller-observation", 41)
    private val factory = mockk<GradleConnectionFactory>()
    private val connection = mockk<ProjectConnection>()
    private val builder = mockk<ModelBuilder<GradleScopedDependencyModel>>()
    private val scripts = CopyOnWriteArrayList<Path>()
    private val argumentLists = CopyOnWriteArrayList<List<String>>()
    private val tokens = CopyOnWriteArrayList<CancellationToken>()
    private val handlers = CopyOnWriteArrayList<ResultHandler<in GradleScopedDependencyModel>>()
    private val model = Model(listOf(Target()))

    init {
        every { factory.getConnection(directory, null) } returns connection
        every { connection.model(GradleScopedDependencyModel::class.java) } returns builder
        listOf(builder).forEach { modelBuilder ->
            every { modelBuilder.withArguments(*anyVararg()) } answers {
                val arguments = firstArg<Array<String>>().toList()
                argumentLists.add(arguments)
                assertTrue("--no-configure-on-demand" in arguments)
                scripts.add(Path.of(arguments[arguments.indexOf("--init-script") + 1]))
                modelBuilder
            }
            val token = slot<CancellationToken>()
            every { modelBuilder.withCancellationToken(capture(token)) } answers {
                tokens.add(token.captured)
                modelBuilder
            }
            every { modelBuilder.setJavaHome(any()) } returns modelBuilder
        }
    }

    private fun producer(javaHome: Path? = null) = GradleScopedDependencyProducer(
        factory,
        javaHome,
        initScriptDirectory = temporary.resolve("init-scripts"),
    )

    private fun ModelBuilder<GradleScopedDependencyModel>.completes(result: GradleScopedDependencyModel?) {
        every { get(any<ResultHandler<in GradleScopedDependencyModel>>()) } answers {
            firstArg<ResultHandler<in GradleScopedDependencyModel>>().onComplete(result)
        }
    }

    private fun ModelBuilder<GradleScopedDependencyModel>.fails(failure: GradleConnectionException) {
        every { get(any<ResultHandler<in GradleScopedDependencyModel>>()) } answers {
            firstArg<ResultHandler<in GradleScopedDependencyModel>>().onFailure(failure)
        }
    }

    private fun ModelBuilder<GradleScopedDependencyModel>.neverCompletes() {
        every { get(any<ResultHandler<in GradleScopedDependencyModel>>()) } answers {
            handlers.add(firstArg())
        }
    }

    @Test
    fun `passes the idle timeout so the daemon it may start does not live for hours`() = runBlocking {
        builder.completes(model)

        producer().resolve(directory, provenance)

        assertEquals("-Dorg.gradle.daemon.idletimeout=600000", argumentLists.single().last())
    }

    @Test
    fun `leases the connection for the whole operation when the factory supports it`() = runBlocking {
        val leasing = mockk<LeasingConnectionFactory>()
        var released = false
        every { leasing.acquire(directory, null) } returns ConnectionLease(connection) { released = true }
        builder.completes(model)

        GradleScopedDependencyProducer(
            leasing,
            null,
            initScriptDirectory = temporary.resolve("init-scripts"),
        ).resolve(directory, provenance)

        assertTrue(released)
        verify(exactly = 0) { leasing.getConnection(any(), any()) }
    }

    @Test
    fun `queries trusted packaged script forwards java home and cancellation and borrows connection`() = runBlocking {
        builder.completes(model)
        val javaHome = Path.of("java-home").toAbsolutePath()

        val result = producer(javaHome).resolve(directory, provenance)

        assertSame(provenance, assertIs<TargetDependencyEvidence.Scoped>(result).snapshots.single().provenance)
        assertContentEquals(GradleScopedInitScript.content, Files.readAllBytes(scripts.single()))
        assertEquals(1, tokens.size)
        verify { builder.setJavaHome(javaHome.toFile()) }
        verify(exactly = 0) { connection.close() }
    }

    @Test
    fun `reuses one content addressed script and repairs a tampered copy`() = runBlocking {
        builder.completes(model)
        val producer = producer()

        producer.resolve(directory, provenance)
        Files.writeString(scripts.single(), "throw new RuntimeException('tampered')")
        producer.resolve(directory, provenance.copy(generation = 42))

        assertEquals(2, scripts.size)
        assertEquals(scripts[0], scripts[1])
        assertContentEquals(GradleScopedInitScript.content, Files.readAllBytes(scripts[1]))
    }

    @Test
    fun `refuses a script directory other users can write`() = runBlocking {
        val shared = Files.createDirectories(temporary.resolve("init-scripts"))
        assumeTrue(Files.getFileAttributeView(shared, PosixFileAttributeView::class.java) != null)
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"))
        builder.completes(model)

        assertSame(TargetDependencyEvidence.Legacy, producer().resolve(directory, provenance))
        verify(exactly = 0) { factory.getConnection(any(), any()) }
    }

    @Test
    fun `a build without source sets yields legacy rather than empty scoped evidence`() = runBlocking {
        builder.completes(Model(emptyList()))

        assertSame(TargetDependencyEvidence.Legacy, producer().resolve(directory, provenance))
    }

    @Test
    fun `malformed or missing models yield legacy`() = runBlocking {
        builder.completes(Model(listOf(null)))
        assertSame(TargetDependencyEvidence.Legacy, producer().resolve(directory, provenance))

        builder.completes(null)
        assertSame(TargetDependencyEvidence.Legacy, producer().resolve(directory, provenance))
    }

    @ParameterizedTest
    @MethodSource("buildFailures")
    fun `failures raised by the user build yield legacy without walking causes`(failure: GradleConnectionException) =
        runBlocking {
            builder.fails(failure)

            assertSame(TargetDependencyEvidence.Legacy, producer().resolve(directory, provenance))
        }

    @Test
    fun `caller cancellation propagates and cancels the gradle operation`() = runBlocking {
        builder.neverCompletes()
        val pending = async { producer().resolve(directory, provenance) }
        withTimeout(5.seconds) { while (handlers.isEmpty()) yield() }

        pending.cancelAndJoin()

        assertFailsWith<CancellationException> { pending.await() }
        assertTrue(tokens.single().isCancellationRequested)
        handlers.single().onFailure(BuildCancelledException("late acknowledgement")) // Ignored after cancellation.
    }

    @Test
    fun `cancelled operation retains lease until failure acknowledgement`() = runBlocking {
        val releases = AtomicInteger()
        val leasing = mockk<LeasingConnectionFactory>()
        every { leasing.acquire(directory, null) } returns ConnectionLease(connection) { releases.incrementAndGet() }
        builder.neverCompletes()
        val pending = async {
            GradleScopedDependencyProducer(leasing, initScriptDirectory = temporary.resolve("init-scripts"))
                .resolve(directory, provenance)
        }
        withTimeout(5.seconds) { while (handlers.isEmpty()) yield() }

        pending.cancelAndJoin()

        assertTrue(tokens.single().isCancellationRequested)
        assertEquals(0, releases.get(), "cancellation alone does not acknowledge completion")
        handlers.single().onFailure(BuildCancelledException("acknowledged"))
        assertEquals(1, releases.get())
        handlers.single().onComplete(model)
        handlers.single().onFailure(BuildCancelledException("duplicate"))
        assertEquals(1, releases.get())
    }

    @Test
    fun `cancel before launch releases lease without starting model fetch`() = runBlocking {
        val releases = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val leasing = mockk<LeasingConnectionFactory>()
        every { leasing.acquire(directory, null) } answers {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            ConnectionLease(connection) { releases.incrementAndGet() }
        }
        builder.neverCompletes()
        val pending = async {
            GradleScopedDependencyProducer(leasing, initScriptDirectory = temporary.resolve("init-scripts"))
                .resolve(directory, provenance)
        }
        withTimeout(5.seconds) { while (entered.count != 0L) yield() }
        pending.cancel()
        proceed.countDown()
        pending.join()

        assertEquals(1, releases.get())
        assertTrue(handlers.isEmpty())
    }

    @Test
    fun `cancellation during launch retains lease until callback`() = runBlocking {
        val releases = AtomicInteger()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val leasing = mockk<LeasingConnectionFactory>()
        every { leasing.acquire(directory, null) } returns ConnectionLease(connection) { releases.incrementAndGet() }
        every { builder.get(any<ResultHandler<in GradleScopedDependencyModel>>()) } answers {
            handlers.add(firstArg())
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val pending = async {
            GradleScopedDependencyProducer(leasing, initScriptDirectory = temporary.resolve("init-scripts"))
                .resolve(directory, provenance)
        }
        withTimeout(5.seconds) { while (entered.count != 0L) yield() }
        pending.cancel()
        assertEquals(0, releases.get())
        proceed.countDown()
        pending.join()
        assertEquals(0, releases.get())
        handlers.single().onComplete(model)
        assertEquals(1, releases.get())
    }

    @Test
    fun `synchronous launch failure releases lease`() = runBlocking {
        val releases = AtomicInteger()
        val leasing = mockk<LeasingConnectionFactory>()
        every { leasing.acquire(directory, null) } returns ConnectionLease(connection) { releases.incrementAndGet() }
        every { builder.get(any<ResultHandler<in GradleScopedDependencyModel>>()) } answers {
            handlers.add(firstArg())
            error("launch failed")
        }

        val result = GradleScopedDependencyProducer(leasing, initScriptDirectory = temporary.resolve("init-scripts"))
            .resolve(directory, provenance)

        assertSame(TargetDependencyEvidence.Legacy, result)
        assertEquals(1, releases.get())
        handlers.single().onComplete(model)
        assertEquals(1, releases.get())
    }

    @Test
    fun `duplicate callbacks after success do not resume twice or release twice`() = runBlocking {
        val releases = AtomicInteger()
        val leasing = mockk<LeasingConnectionFactory>()
        every { leasing.acquire(directory, null) } returns ConnectionLease(connection) { releases.incrementAndGet() }
        builder.neverCompletes()
        val pending = async {
            GradleScopedDependencyProducer(leasing, initScriptDirectory = temporary.resolve("init-scripts"))
                .resolve(directory, provenance)
        }
        withTimeout(5.seconds) { while (handlers.isEmpty()) yield() }
        handlers.single().onComplete(model)
        handlers.single().onComplete(model)
        handlers.single().onFailure(BuildCancelledException("late duplicate"))

        assertIs<TargetDependencyEvidence.Scoped>(pending.await())
        assertEquals(1, releases.get())
    }

    @Test
    fun `deadline cancels the gradle operation and yields legacy`() = runBlocking {
        builder.neverCompletes()

        assertSame(
            TargetDependencyEvidence.Legacy,
            producer().resolve(directory, provenance, timeout = 50.milliseconds),
        )
        assertTrue(tokens.single().isCancellationRequested)
    }

    @Test
    fun `init script failures yield legacy without an isolated gradle home retry`() = runBlocking {
        builder.fails(BuildException("A problem occurred evaluating init script", RuntimeException("user init.d")))

        assertSame(TargetDependencyEvidence.Legacy, producer().resolve(directory, provenance))
        verify(exactly = 1) { factory.getConnection(directory, null) }
        verify(exactly = 0) { factory.getConnection(any(), match<java.io.File> { true }) }
    }

    @Test
    fun `no absolute user home means no trusted script directory and legacy evidence`() = runBlocking {
        builder.completes(model)

        val producer = GradleScopedDependencyProducer(factory, initScriptDirectory = null)

        assertSame(TargetDependencyEvidence.Legacy, producer.resolve(directory, provenance))
        verify(exactly = 0) { factory.getConnection(any(), any()) }
    }

    @Test
    fun `a jar replaced at the same path is observed as a different artifact`() = runBlocking {
        val jar = Files.write(temporary.resolve("lib.jar"), ByteArray(3))
        Files.setLastModifiedTime(jar, FileTime.fromMillis(1_000_000))
        val missing = temporary.resolve("missing.jar")
        builder.completes(Model(listOf(Target(listOf(jar, missing)))))
        val producer = producer()

        val before = assertIs<TargetDependencyEvidence.Scoped>(producer.resolve(directory, provenance))
        Files.write(jar, ByteArray(5))
        Files.setLastModifiedTime(jar, FileTime.fromMillis(2_000_000))
        val after = assertIs<TargetDependencyEvidence.Scoped>(producer.resolve(directory, provenance))

        val (stampedBefore, absentBefore) = before.snapshots.single().artifacts
        val (stampedAfter, absentAfter) = after.snapshots.single().artifacts
        assertEquals(ArtifactStamp(3, 1_000_000), stampedBefore.stamp)
        assertEquals(ArtifactStamp(5, 2_000_000), stampedAfter.stamp)
        assertNotEquals(stampedBefore, stampedAfter, "same path, different content: a different fact")
        assertNull(absentBefore.stamp, "an unreadable entry keeps a null stamp")
        assertEquals(absentBefore, absentAfter)
    }

    private data class Model(override val targets: List<GradleSourceSetModel?>?) : GradleScopedDependencyModel {
        override val buildDirectory: String = Path.of("model-root").toAbsolutePath().toString()
    }

    private class Target(paths: List<Path> = emptyList()) : GradleSourceSetModel {
        override val projectPath = ":"
        override val projectDirectory: String = Path.of("model-root").toAbsolutePath().toString()
        override val sourceSet = "main"
        override val configuration: String? = null
        override val enrichmentConfiguration = "compileClasspath"
        override val state = "COMPLETE"
        override val sourceRoots = emptyList<String?>()
        override val artifacts: List<GradleClasspathEntryModel?> = paths.map(::Entry)
    }

    private class Entry(path: Path) : GradleClasspathEntryModel {
        override val path: String = path.toString()
        override val coordinateCandidates = emptyList<GradleModuleCoordinatesModel?>()
    }

    companion object {
        @JvmStatic
        fun buildFailures(): List<GradleConnectionException> = listOf(
            BuildException("user build", LinkageError("broken plugin classpath")),
            BuildException("user build", java.util.concurrent.CancellationException("thrown by build logic")),
            BuildException("user build", StackOverflowError()),
            BuildCancelledException("cancelled outside this caller"),
            UnknownModelException("unsupported model"),
        )
    }
}
