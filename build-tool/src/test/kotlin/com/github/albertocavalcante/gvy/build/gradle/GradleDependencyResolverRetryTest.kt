package com.github.albertocavalcante.gvy.build.gradle

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.DomainObjectSet
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder

        val error = RuntimeException(
            "Could not open cp_init generic class cache for initialization script '~/.gradle/init.d/foo.gradle'",
        )
        // Fail both attempts; this test only asserts we try an isolated user home fallback.
        every { modelBuilder.get() } throws error
        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
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
        val ideaProject = mockk<IdeaProject>()
        val emptyModules = mockk<DomainObjectSet<IdeaModule>>(relaxed = true)

        every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder
        every { ideaProject.modules } returns emptyModules

        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
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

        every { modelBuilder.withArguments(any(), any(), any(), any()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(any(), any()) } returns modelBuilder

        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
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

    /** Mocks one connection whose IdeaProject fetch runs [onModelFetch] with the 1-based call number. */
    private class ModelFetchFixture(private val projectDir: Path, onModelFetch: (Int) -> Unit) {
        var modelCalls = 0
            private set

        private val connectionFactory = mockk<GradleConnectionFactory>()

        init {
            projectDir.resolve("build.gradle").toFile().writeText("plugins { id 'java' }")
            val connection = mockk<ProjectConnection>()
            val modelBuilder = mockk<ModelBuilder<IdeaProject>>()
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
            val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
            every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
            every { buildEnvBuilder.get() } returns buildEnvironment
            every { buildEnvironment.gradle.gradleVersion } returns "8.5"
            every { connection.model(IdeaProject::class.java) } returns modelBuilder
            every { connectionFactory.getConnection(any(), any()) } returns connection
        }

        fun resolve() = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = GradleDependencyResolver.RetryConfig(
                maxAttempts = 4,
                initialDelayMs = 0L,
                backoffMultiplier = 1.0,
            ),
        ).resolve(workspaceRoot = projectDir, onProgress = null)
    }

    private companion object {
        const val DISTRIBUTION_URL = "https://services.gradle.org/distributions/gradle-9.2.1-bin.zip"
    }
}
