package com.github.albertocavalcante.gvy.build.gradle

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
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GradleScopedDependencyProducerTest {
    @TempDir
    lateinit var temporary: Path

    private val directory = Path.of("transport-project").toAbsolutePath()
    private val isolatedHome = Path.of("isolated-home").toAbsolutePath()
    private val provenance = DependencyProvenance("caller-observation", 41)
    private val factory = mockk<GradleConnectionFactory>()
    private val connection = mockk<ProjectConnection>()
    private val isolatedConnection = mockk<ProjectConnection>()
    private val builder = mockk<ModelBuilder<GradleScopedDependencyModel>>()
    private val isolatedBuilder = mockk<ModelBuilder<GradleScopedDependencyModel>>()
    private val scripts = CopyOnWriteArrayList<Path>()
    private val tokens = CopyOnWriteArrayList<CancellationToken>()
    private val handlers = CopyOnWriteArrayList<ResultHandler<in GradleScopedDependencyModel>>()
    private val model = Model(listOf(Target()))

    init {
        every { factory.getConnection(directory, null) } returns connection
        every { factory.getConnection(directory, isolatedHome.toFile()) } returns isolatedConnection
        every { connection.model(GradleScopedDependencyModel::class.java) } returns builder
        every { isolatedConnection.model(GradleScopedDependencyModel::class.java) } returns isolatedBuilder
        listOf(builder, isolatedBuilder).forEach { modelBuilder ->
            every { modelBuilder.withArguments(*anyVararg()) } answers {
                val arguments = firstArg<Array<String>>().toList()
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
        isolatedGradleUserHome = { isolatedHome },
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
            verify(exactly = 0) { factory.getConnection(directory, isolatedHome.toFile()) }
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
    fun `deadline cancels the gradle operation and yields legacy`() = runBlocking {
        builder.neverCompletes()

        assertSame(
            TargetDependencyEvidence.Legacy,
            producer().resolve(directory, provenance, timeout = 50.milliseconds),
        )
        assertTrue(tokens.single().isCancellationRequested)
    }

    @Test
    fun `init script failures retry once with an isolated gradle user home`() = runBlocking {
        builder.fails(BuildException("A problem occurred evaluating init script", RuntimeException("user init.d")))
        isolatedBuilder.completes(model)

        assertIs<TargetDependencyEvidence.Scoped>(producer().resolve(directory, provenance))
        verify(exactly = 1) { factory.getConnection(directory, isolatedHome.toFile()) }
    }

    @Test
    fun `a failed isolated retry yields legacy`() = runBlocking {
        builder.fails(BuildException("A problem occurred evaluating init script", null))
        isolatedBuilder.fails(BuildException("still broken", null))

        assertSame(TargetDependencyEvidence.Legacy, producer().resolve(directory, provenance))
    }

    private data class Model(override val targets: List<GradleSourceSetModel?>?) : GradleScopedDependencyModel {
        override val buildDirectory: String = Path.of("model-root").toAbsolutePath().toString()
    }

    private class Target : GradleSourceSetModel {
        override val projectPath = ":"
        override val projectDirectory: String = Path.of("model-root").toAbsolutePath().toString()
        override val sourceSet = "main"
        override val configuration: String? = null
        override val enrichmentConfiguration = "compileClasspath"
        override val state = "COMPLETE"
        override val sourceRoots = emptyList<String?>()
        override val artifacts = emptyList<GradleClasspathEntryModel?>()
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
