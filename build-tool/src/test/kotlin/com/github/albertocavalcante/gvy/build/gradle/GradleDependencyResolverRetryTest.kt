package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GradleDependencyResolverRetryTest {

    @Test
    fun `retries with isolated Gradle user home when init scripts break model fetch`(@TempDir projectDir: Path) {
        projectDir.resolve("build.gradle").toFile().writeText(
            """
            plugins { id 'java' }
            """.trimIndent(),
        )

        val connectionFactory = mockk<GradleConnectionFactory>()

        val firstConnection = mockk<ProjectConnection>()
        val secondConnection = mockk<ProjectConnection>()

        val modelBuilder = mockk<ModelBuilder<IdeaProject>>()

        every { modelBuilder.withCancellationToken(any()) } returns modelBuilder
        every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder

        val error = RuntimeException(
            "Could not open cp_init generic class cache for initialization script '~/.gradle/init.d/foo.gradle'",
        )
        // Fail both attempts; this test only asserts we try an isolated user home fallback.
        every { modelBuilder.get() } throws error
        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
        every { buildEnvBuilder.withCancellationToken(any()) } returns buildEnvBuilder
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { firstConnection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { secondConnection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        every { firstConnection.model(IdeaProject::class.java) } returns modelBuilder
        every { secondConnection.model(IdeaProject::class.java) } returns modelBuilder

        val gradleUserHomeArgs = mutableListOf<File?>()
        val connections = listOf(firstConnection, secondConnection)
        var callCount = 0

        every { connectionFactory.getConnection(any(), any()) } answers {
            gradleUserHomeArgs.add(secondArg())
            connections[callCount++]
        }

        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = GradleDependencyResolver.RetryConfig(initialDelayMs = 0),
        )

        val result = resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        assertEquals(0, result.dependencies.size)
        assertEquals(0, result.sourceDirectories.size)

        verify(exactly = 2) { connectionFactory.getConnection(any(), any()) }
        assertEquals(2, gradleUserHomeArgs.size)
        assertNull(gradleUserHomeArgs[0], "First attempt should use the default Gradle user home")

        val isolated = gradleUserHomeArgs[1]
        assertNotNull(isolated, "Retry attempt should use an isolated Gradle user home directory")
        assertEquals("gradle-user-home", isolated.name)
        assertEquals(".groovy-lsp", isolated.parentFile.name)
        assertTrue(isolated.exists(), "Retry attempt should create the isolated Gradle user home directory")
        assertTrue(isolated.isDirectory, "Isolated Gradle user home should be a directory")
    }

    @Test
    fun `does not retry when model fetch fails for unrelated reasons`(@TempDir projectDir: Path) {
        projectDir.resolve("build.gradle").toFile().writeText(
            """
            plugins { id 'java' }
            """.trimIndent(),
        )

        val connectionFactory = mockk<GradleConnectionFactory>()
        val connection = mockk<ProjectConnection>()
        val modelBuilder = mockk<ModelBuilder<IdeaProject>>()
        every { modelBuilder.withCancellationToken(any()) } returns modelBuilder
        every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder
        every { modelBuilder.get() } throws RuntimeException("some other failure")
        every { connection.model(IdeaProject::class.java) } returns modelBuilder

        every { connectionFactory.getConnection(any(), any()) } returns connection

        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = GradleDependencyResolver.RetryConfig(initialDelayMs = 0),
        )
        resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        verify(exactly = 1) { connectionFactory.getConnection(any(), any()) }
        verify { connectionFactory.getConnection(any(), null) }
    }

    @Test
    fun `retries on lock timeout exception and succeeds on second attempt`(@TempDir projectDir: Path) {
        projectDir.resolve("build.gradle").toFile().writeText(
            """
            plugins { id 'java' }
            """.trimIndent(),
        )

        val connectionFactory = mockk<GradleConnectionFactory>()
        val connection = mockk<ProjectConnection>()
        val modelBuilder = mockk<ModelBuilder<IdeaProject>>()
        every { modelBuilder.withCancellationToken(any()) } returns modelBuilder
        val ideaProject = mockk<IdeaProject>()
        val emptyModules = mockk<DomainObjectSet<IdeaModule>>(relaxed = true)

        every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder
        every { ideaProject.modules } returns emptyModules

        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)

        every { buildEnvBuilder.withCancellationToken(any()) } returns buildEnvBuilder
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        // First attempt fails with lock timeout, second succeeds
        val lockTimeoutError = RuntimeException(
            "Timeout waiting to lock build logic queue. It is currently in use by another process.",
        )
        var callCount = 0
        every { modelBuilder.get() } answers {
            callCount++
            if (callCount == 1) {
                throw lockTimeoutError
            }
            ideaProject
        }
        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every { connectionFactory.getConnection(any(), any()) } returns connection

        // Use zero-delay config for fast test execution
        val fastRetryConfig = GradleDependencyResolver.RetryConfig(
            maxAttempts = 4,
            initialDelayMs = 0L,
            backoffMultiplier = 1.0,
        )
        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = fastRetryConfig,
        )
        val result = resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        // Verify it retried and succeeded
        assertEquals(0, result.dependencies.size) // Empty project, no deps
        assertEquals(2, callCount, "Should have retried after lock timeout")
    }

    @Test
    fun `gives up after max retry attempts on persistent lock timeout`(@TempDir projectDir: Path) {
        projectDir.resolve("build.gradle").toFile().writeText(
            """
            plugins { id 'java' }
            """.trimIndent(),
        )

        val connectionFactory = mockk<GradleConnectionFactory>()
        val connection = mockk<ProjectConnection>()
        val modelBuilder = mockk<ModelBuilder<IdeaProject>>()
        every { modelBuilder.withCancellationToken(any()) } returns modelBuilder

        every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder

        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)

        every { buildEnvBuilder.withCancellationToken(any()) } returns buildEnvBuilder
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        // Always fail with lock timeout
        val lockTimeoutError = RuntimeException(
            "Timeout waiting to lock build logic queue. It is currently in use by another process.",
        )
        var attemptCount = 0
        every { modelBuilder.get() } answers {
            attemptCount++
            throw lockTimeoutError
        }
        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every { connectionFactory.getConnection(any(), any()) } returns connection

        // Use zero-delay config for fast test execution
        val fastRetryConfig = GradleDependencyResolver.RetryConfig(
            maxAttempts = 4,
            initialDelayMs = 0L,
            backoffMultiplier = 1.0,
        )
        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = fastRetryConfig,
        )
        val result = resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        // Should have tried MAX_ATTEMPTS (4) times then given up
        assertEquals(4, attemptCount, "Should have retried 4 times before giving up")
        assertEquals(0, result.dependencies.size)
        assertEquals(0, result.sourceDirectories.size)
    }

    @Test
    fun `retries transient distribution download timeout and succeeds on next attempt`(@TempDir projectDir: Path) {
        val downloadTimeout = GradleConnectionException(
            "Could not install Gradle distribution from '$DISTRIBUTION_URL'.",
            IOException(
                "Downloading from $DISTRIBUTION_URL failed: timeout (10000ms)",
                SocketTimeoutException("Read timed out"),
            ),
        )
        val fixture = ModelFetchFixture(projectDir) { call -> if (call == 1) throw downloadTimeout }

        val result = fixture.resolve()

        assertEquals(2, fixture.modelCalls, "Should retry after a transient distribution download timeout")
        assertTrue(result.isUsable, "Second attempt succeeded, so resolution should be usable")
    }

    @Test
    fun `does not retry distribution download that returned HTTP 404`(@TempDir projectDir: Path) {
        val notFound = GradleConnectionException(
            "Could not install Gradle distribution from '$DISTRIBUTION_URL'.",
            IOException("Server returned HTTP response code: 404 for URL: $DISTRIBUTION_URL"),
        )
        val fixture = ModelFetchFixture(projectDir) { throw notFound }

        val result = fixture.resolve()

        assertEquals(1, fixture.modelCalls, "A missing distribution version is deterministic and must not be retried")
        assertFalse(result.isUsable)
    }

    @Test
    fun `stops retrying transient failures once the resolving thread is interrupted`(@TempDir projectDir: Path) {
        val lockTimeout = RuntimeException("Timeout waiting to lock build logic queue.")
        val fixture = ModelFetchFixture(projectDir) {
            Thread.currentThread().interrupt()
            throw lockTimeout
        }

        try {
            val result = fixture.resolve()

            assertEquals(1, fixture.modelCalls, "An interrupted (cancelled) resolution must not keep retrying")
            assertFalse(result.isUsable)
            assertTrue(Thread.currentThread().isInterrupted, "Interrupt status must be preserved for the caller")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancelling during retry backoff ends the resolution within one step`(@TempDir projectDir: Path) {
        val firstFailure = CountDownLatch(1)
        // A backoff far longer than the bound: only cancellation can end it in time.
        val slowRetries = GradleDependencyResolver.RetryConfig(maxAttempts = 4, initialDelayMs = 60_000L)
        val fixture = ModelFetchFixture(projectDir, slowRetries) {
            firstFailure.countDown()
            error("Timeout waiting to lock build logic queue.")
        }
        val cancellation = ResolutionCancellation()

        val resolution = CompletableFuture.supplyAsync { fixture.resolve(cancellation) }
        assertTrue(firstFailure.await(BOUND_SECONDS, TimeUnit.SECONDS), "First attempt never ran")
        cancellation.cancel()

        assertCancelledWithinBound(resolution)
        assertEquals(1, fixture.modelCalls, "A cancelled resolution must not start another attempt")
    }

    @Test
    fun `cancelling a blocked model fetch cancels its tooling api token and does not retry`(@TempDir projectDir: Path) {
        val fetching = CountDownLatch(1)
        val fixture = ModelFetchFixture(projectDir) {
            fetching.countDown()
            val token = tokens.lastOrNull() ?: fail("IdeaProject fetch ran without a cancellation token")
            // Stands in for the Tooling API, which polls its token while an operation or download runs.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
            while (!token.isCancellationRequested && System.nanoTime() < deadline) Thread.onSpinWait()
            throw BuildCancelledException("Build cancelled.")
        }
        val cancellation = ResolutionCancellation()

        val resolution = CompletableFuture.supplyAsync { fixture.resolve(cancellation) }
        assertTrue(fetching.await(BOUND_SECONDS, TimeUnit.SECONDS), "Model fetch never started")
        cancellation.cancel()

        assertCancelledWithinBound(resolution)
        assertTrue(fixture.tokens.single().isCancellationRequested, "The Tooling API token must be cancelled")
        assertEquals(1, fixture.modelCalls, "A cancelled fetch must not be retried")
        assertEquals(1, fixture.connectionsOpened, "A cancelled fetch must not fall back to an isolated user home")
    }

    @Test
    fun `an already cancelled resolution never connects`(@TempDir projectDir: Path) {
        val fixture = ModelFetchFixture(projectDir) {}
        val cancellation = ResolutionCancellation().apply { cancel() }

        assertThrows<CancellationException> { fixture.resolve(cancellation) }
        assertEquals(0, fixture.connectionsOpened)
    }

    private fun assertCancelledWithinBound(resolution: CompletableFuture<*>) {
        val failure = assertThrows<ExecutionException>("Resolution was not cancelled within ${BOUND_SECONDS}s") {
            resolution.get(BOUND_SECONDS, TimeUnit.SECONDS)
        }
        assertIs<CancellationException>(failure.cause, "Expected cancellation, got ${failure.cause}")
    }

    /** Mocks one connection whose IdeaProject fetch runs [onModelFetch] with the 1-based call number. */
    private class ModelFetchFixture(
        private val projectDir: Path,
        private val retryConfig: GradleDependencyResolver.RetryConfig = ZERO_DELAY_RETRIES,
        onModelFetch: ModelFetchFixture.(Int) -> Unit,
    ) {
        @Volatile
        var modelCalls = 0
            private set

        /** Connections requested, including the isolated-user-home fallback. */
        @Volatile
        var connectionsOpened = 0
            private set

        /** Tooling API tokens attached to IdeaProject fetches, in call order. */
        val tokens = CopyOnWriteArrayList<CancellationToken>()

        private val connectionFactory = mockk<GradleConnectionFactory>()

        init {
            projectDir.resolve("build.gradle").toFile().writeText("plugins { id 'java' }")
            val connection = mockk<ProjectConnection>()
            val modelBuilder = mockk<ModelBuilder<IdeaProject>>()
            every { modelBuilder.withCancellationToken(any()) } answers {
                tokens.add(firstArg())
                modelBuilder
            }
            val ideaProject = mockk<IdeaProject>()
            every { ideaProject.modules } returns mockk<DomainObjectSet<IdeaModule>>(relaxed = true)
            every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
            every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder
            every { modelBuilder.get() } answers {
                modelCalls++
                onModelFetch(modelCalls)
                ideaProject
            }
            val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
            every { buildEnvBuilder.withCancellationToken(any()) } returns buildEnvBuilder
            val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
            every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
            every { buildEnvBuilder.get() } returns buildEnvironment
            every { buildEnvironment.gradle.gradleVersion } returns "8.5"
            every { connection.model(IdeaProject::class.java) } returns modelBuilder
            every { connectionFactory.getConnection(any(), any()) } answers {
                connectionsOpened++
                connection
            }
        }

        fun resolve(cancellation: ResolutionCancellation = ResolutionCancellation()) = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = retryConfig,
        ).resolve(workspaceRoot = projectDir, onProgress = null, cancellation = cancellation)
    }

    private companion object {
        val ZERO_DELAY_RETRIES = GradleDependencyResolver.RetryConfig(
            maxAttempts = 4,
            initialDelayMs = 0L,
            backoffMultiplier = 1.0,
        )
        const val BOUND_SECONDS = 5L

        const val DISTRIBUTION_URL = "https://services.gradle.org/distributions/gradle-9.2.1-bin.zip"
    }
}
