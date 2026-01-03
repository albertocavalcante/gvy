package com.github.albertocavalcante.groovylsp.buildtool.gradle

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class GradleDependencyResolverTest {

    private val connectionFactory = mockk<GradleConnectionFactory>()
    private val compatibilityService = mockk<GradleCompatibilityService>()
    private val failureAnalyzer = mockk<GradleFailureAnalyzer>()

    private val connection = mockk<ProjectConnection>(relaxed = true)
    private val modelBuilder = mockk<org.gradle.tooling.ModelBuilder<IdeaProject>>(relaxed = true)
    private val buildEnvBuilder = mockk<org.gradle.tooling.ModelBuilder<BuildEnvironment>>(relaxed = true)
    private val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
    private val ideaProject = mockk<IdeaProject>(relaxed = true)

    @Test
    fun `should set java home on model builder when configured`() {
        // Given
        val projectDir = Paths.get("/tmp/project")
        val javaHome = Paths.get("/tmp/jdk21")

        // Setup mocks
        every { connectionFactory.getConnection(any(), any()) } returns connection

        // Return our modelBuilder mock instead of a relaxed one
        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every {
            modelBuilder.withArguments(
                "-Dorg.gradle.daemon=true",
                "-Dorg.gradle.parallel=true",
                "-Dorg.gradle.configureondemand=true",
                "-Dorg.gradle.vfs.watch=true",
            )
        } returns modelBuilder
        every {
            modelBuilder.setJvmArguments("-Xmx1g", "-XX:+UseG1GC")
        } returns modelBuilder
        every { modelBuilder.setJavaHome(any()) } returns modelBuilder

        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"

        // Assume compatible
        every { compatibilityService.isCompatible(any(), any()) } returns true
        every { modelBuilder.get() } returns ideaProject

        // For relaxed or missing answers in failure analyzer
        every { failureAnalyzer.isTransient(any()) } returns false
        every { failureAnalyzer.isInitScriptError(any()) } returns false

        // When
        val resolver = GradleDependencyResolver(
            connectionFactory = connectionFactory,
            compatibilityService = compatibilityService,
            failureAnalyzer = failureAnalyzer,
            javaHome = javaHome,
        )
        resolver.resolveDependencies(projectDir)

        // Then
        verify { modelBuilder.setJavaHome(javaHome.toFile()) }
    }

    @Test
    fun `should capture jdk major version from release file`(
        @org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path,
    ) {
        // Given
        val jdkHome = tempDir.resolve("jdk-17")
        java.nio.file.Files.createDirectories(jdkHome)
        val releaseFile = jdkHome.resolve("release")
        java.nio.file.Files.writeString(releaseFile, "JAVA_VERSION=\"17.0.2\"")

        // Setup mocks
        every { connectionFactory.getConnection(any(), any()) } returns connection

        // Stub both model() builder and getModel() direct call
        every { connection.model(BuildEnvironment::class.java) } returns buildEnvBuilder
        every { connection.getModel(BuildEnvironment::class.java) } returns buildEnvironment

        every { buildEnvBuilder.get() } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "7.3"
        // We expect check compatible to be called with 17
        every { compatibilityService.isCompatible("7.3", 17) } returns true

        every { connection.model(IdeaProject::class.java) } returns modelBuilder
        every { modelBuilder.withArguments(*anyVararg()) } returns modelBuilder
        every { modelBuilder.setJvmArguments(*anyVararg()) } returns modelBuilder
        every { modelBuilder.setJavaHome(any()) } returns modelBuilder
        every { modelBuilder.get() } returns ideaProject

        val resolver = GradleDependencyResolver(
            connectionFactory = connectionFactory,
            compatibilityService = compatibilityService,
            failureAnalyzer = failureAnalyzer,
            javaHome = jdkHome,
        )

        // When
        resolver.resolveDependencies(Paths.get("/tmp/project"))

        // Then
        verify { compatibilityService.isCompatible("7.3", 17) }
    }

    @Test
    fun `should capture jdk 8 version from release file`(
        @org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path,
    ) {
        // Given
        val jdkHome = tempDir.resolve("jdk-8")
        java.nio.file.Files.createDirectories(jdkHome)
        val releaseFile = jdkHome.resolve("release")
        // JDK 8 format: 1.8.0_xxx
        java.nio.file.Files.writeString(releaseFile, "JAVA_VERSION=\"1.8.0_312\"")

        // Setup mocks
        every { connectionFactory.getConnection(any(), any()) } returns connection
        every { connection.getModel(BuildEnvironment::class.java) } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "6.8" // Compatible with 8
        every { compatibilityService.isCompatible("6.8", 8) } returns true

        val resolver = GradleDependencyResolver(
            connectionFactory = connectionFactory,
            compatibilityService = compatibilityService,
            failureAnalyzer = failureAnalyzer,
            javaHome = jdkHome,
        )

        // Force resolution to trigger check
        runCatching { resolver.resolveDependencies(Paths.get("/tmp/project")) }

        // Then
        verify { compatibilityService.isCompatible("6.8", 8) }
    }

    @Test
    fun `should fallback to runtime version when release file missing`(
        @org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path,
    ) {
        // Given empty java home
        val jdkHome = tempDir.resolve("empty-jdk")
        java.nio.file.Files.createDirectories(jdkHome)

        // Setup mocks
        every { connectionFactory.getConnection(any(), any()) } returns connection
        every { connection.getModel(BuildEnvironment::class.java) } returns buildEnvironment
        every { buildEnvironment.gradle.gradleVersion } returns "8.0"

        val runtimeVersion = Runtime.version().feature()
        every { compatibilityService.isCompatible("8.0", runtimeVersion) } returns true

        val resolver = GradleDependencyResolver(
            connectionFactory = connectionFactory,
            compatibilityService = compatibilityService,
            failureAnalyzer = failureAnalyzer,
            javaHome = jdkHome,
        )

        // Force resolution
        runCatching { resolver.resolveDependencies(Paths.get("/tmp/project")) }

        // Then
        verify { compatibilityService.isCompatible("8.0", runtimeVersion) }
    }
}
