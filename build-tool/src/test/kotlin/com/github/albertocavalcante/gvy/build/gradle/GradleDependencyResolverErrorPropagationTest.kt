package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.ResolutionCodes
import com.github.albertocavalcante.gvy.build.ResolutionStatus
import io.mockk.every
import io.mockk.mockk
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests that verify error status is properly propagated from the resolver
 * to the returned WorkspaceResolution, enabling the client to show
 * actionable error notifications.
 */
class GradleDependencyResolverErrorPropagationTest {

    @Test
    fun `returns Failed status when toolchain provisioning fails`(@TempDir projectDir: Path) {
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

        // Mock BuildEnvironment for compatibility check
        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        // Simulate toolchain provisioning error
        val toolchainError = RuntimeException(
            "Cannot find a Java installation on your machine (Mac OS X 15.6 aarch64) matching: " +
                "{languageVersion=17, vendor=any vendor, implementation=vendor-specific}. " +
                "Toolchain download repositories have not been configured.",
        )
        every { modelBuilder.get() } throws toolchainError
        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every { connectionFactory.getConnection(any(), any()) } returns connection

        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = GradleDependencyResolver.RetryConfig(maxAttempts = 1, initialDelayMs = 0),
        )
        val result = resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        // Verify the status is Failed with correct error code
        val failedStatus = assertIs<ResolutionStatus.Failed>(result.status)
        assertEquals(ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED, failedStatus.code)
        assertTrue(
            failedStatus.message.contains("Java") || failedStatus.message.contains("toolchain"),
            "Error message should mention Java or toolchain",
        )
    }

    @Test
    fun `returns Failed status when JDK class file version mismatch occurs`(@TempDir projectDir: Path) {
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

        // Mock BuildEnvironment for compatibility check
        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        // Simulate JDK mismatch error
        val jdkMismatchError = IllegalArgumentException("Unsupported class file major version 65")
        every { modelBuilder.get() } throws jdkMismatchError
        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every { connectionFactory.getConnection(any(), any()) } returns connection

        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = GradleDependencyResolver.RetryConfig(maxAttempts = 1, initialDelayMs = 0),
        )
        val result = resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        // Verify the status is Failed with correct error code
        val failedStatus = assertIs<ResolutionStatus.Failed>(result.status)
        assertEquals(ResolutionCodes.GRADLE_JDK_INCOMPATIBLE, failedStatus.code)
    }

    @Test
    fun `returns Failed status when generic resolution fails`(@TempDir projectDir: Path) {
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

        // Mock BuildEnvironment for compatibility check
        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        // Simulate generic error
        val genericError = RuntimeException("Some unexpected build failure")
        every { modelBuilder.get() } throws genericError
        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every { connectionFactory.getConnection(any(), any()) } returns connection

        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = GradleDependencyResolver.RetryConfig(maxAttempts = 1, initialDelayMs = 0),
        )
        val result = resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        // Verify the status is Failed (with generic error code)
        val failedStatus = assertIs<ResolutionStatus.Failed>(result.status)
        assertEquals(ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED, failedStatus.code)
    }

    @Test
    fun `includes error details in Failed status for toolchain error`(@TempDir projectDir: Path) {
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

        // Mock BuildEnvironment for compatibility check
        val buildEnvBuilder = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        // Simulate toolchain provisioning error with specific details
        val toolchainError = RuntimeException(
            "ToolchainProvisioningException: Cannot find a Java installation on your machine " +
                "(Mac OS X 15.6 aarch64) matching: {languageVersion=17, vendor=any vendor}. " +
                "Toolchain download repositories have not been configured.",
        )
        every { modelBuilder.get() } throws toolchainError
        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every { connectionFactory.getConnection(any(), any()) } returns connection

        val resolver = GradleBuildTool(
            connectionFactory = connectionFactory,
            retryConfig = GradleDependencyResolver.RetryConfig(maxAttempts = 1, initialDelayMs = 0),
        )
        val result = resolver.resolve(workspaceRoot = projectDir, onProgress = null)

        // Verify the status includes error details
        val failedStatus = assertIs<ResolutionStatus.Failed>(result.status)
        assertEquals(ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED, failedStatus.code)

        // The details should contain toolchain error info - use assertIs to fail explicitly if type is wrong
        val details = assertIs<GradleFailureAnalyzer.ToolchainErrorInfo>(failedStatus.details)
        assertEquals(17, details.requiredVersion)
        assertTrue(details.platform?.contains("Mac OS X") == true || details.platform?.contains("aarch64") == true)
    }
}
