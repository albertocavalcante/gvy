package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class BuildToolManagerTest {

    @TempDir
    lateinit var tempDir: Path

    // Test doubles implement marker interfaces for type-safe filtering
    private val bspBuildTool = FakeBspBuildTool { hasBspDir(it) }
    private val gradleBuildTool = FakeGradleBuildTool { hasGradleFiles(it) }
    private val mavenBuildTool = FakeBuildTool("Maven") { hasMavenFiles(it) }

    private fun hasBspDir(workspaceRoot: Path): Boolean = workspaceRoot.resolve(".bsp").toFile().exists()

    private fun hasGradleFiles(workspaceRoot: Path): Boolean =
        workspaceRoot.resolve("build.gradle").toFile().exists() ||
            workspaceRoot.resolve("build.gradle.kts").toFile().exists()

    private fun hasMavenFiles(workspaceRoot: Path): Boolean = workspaceRoot.resolve("pom.xml").toFile().exists()

    @Nested
    inner class AutoStrategy {

        @Test
        fun `AUTO strategy returns BSP when bsp directory exists for gradle project`() {
            // Setup: Gradle project with BSP
            setupGradleProject()
            setupBspConnection("gradle")

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.AUTO,
            )

            val result = manager.detectBuildTool(tempDir)

            assertEquals("BSP", result?.name)
        }

        @Test
        fun `AUTO strategy returns Gradle when no bsp directory exists`() {
            setupGradleProject()

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.AUTO,
            )

            val result = manager.detectBuildTool(tempDir)

            assertEquals("Gradle", result?.name)
        }

        @Test
        fun `AUTO strategy returns Maven for maven-only project`() {
            setupMavenProject()

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.AUTO,
            )

            val result = manager.detectBuildTool(tempDir)

            assertEquals("Maven", result?.name)
        }
    }

    @Nested
    inner class NativeOnlyStrategy {

        @Test
        fun `NATIVE_ONLY strategy skips BSP for gradle projects even when bsp dir exists`() {
            setupGradleProject()
            setupBspConnection("gradle")

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.NATIVE_ONLY,
            )

            val result = manager.detectBuildTool(tempDir)

            assertEquals("Gradle", result?.name)
        }

        @Test
        fun `NATIVE_ONLY strategy still uses BSP for non-gradle projects like Bazel`() {
            // Only BSP, no gradle or maven files
            setupBspConnection("bazelbsp")

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.NATIVE_ONLY,
            )

            val result = manager.detectBuildTool(tempDir)

            // BSP is still detected for non-Gradle projects
            assertEquals("BSP", result?.name)
        }

        @Test
        fun `NATIVE_ONLY strategy returns Gradle for gradle project without bsp`() {
            setupGradleProject()

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.NATIVE_ONLY,
            )

            val result = manager.detectBuildTool(tempDir)

            assertEquals("Gradle", result?.name)
        }
    }

    @Nested
    inner class BspPreferredStrategy {

        @Test
        fun `BSP_PREFERRED strategy uses BSP when available for gradle project`() {
            setupGradleProject()
            setupBspConnection("gradle")

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.BSP_PREFERRED,
            )

            val result = manager.detectBuildTool(tempDir)

            assertEquals("BSP", result?.name)
        }

        @Test
        fun `BSP_PREFERRED strategy falls back to native when no BSP available`() {
            setupGradleProject()
            // No BSP setup

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.BSP_PREFERRED,
            )

            val result = manager.detectBuildTool(tempDir)

            assertEquals("Gradle", result?.name)
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `returns null when no build tool matches`() {
            // Empty directory

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
                gradleBuildStrategy = GradleBuildStrategy.AUTO,
            )

            val result = manager.detectBuildTool(tempDir)

            assertNull(result)
        }

        @Test
        fun `empty build tools list returns null`() {
            setupGradleProject()

            val manager = BuildToolManager(
                buildTools = emptyList(),
                gradleBuildStrategy = GradleBuildStrategy.AUTO,
            )

            val result = manager.detectBuildTool(tempDir)

            assertNull(result)
        }

        @Test
        fun `default strategy is AUTO`() {
            setupGradleProject()
            setupBspConnection("gradle")

            val manager = BuildToolManager(
                buildTools = listOf(bspBuildTool, gradleBuildTool, mavenBuildTool),
            )

            val result = manager.detectBuildTool(tempDir)

            // AUTO behavior: BSP detected first
            assertEquals("BSP", result?.name)
        }
    }

    // Helper methods

    private fun setupGradleProject() {
        tempDir.resolve("build.gradle").createFile()
    }

    private fun setupMavenProject() {
        tempDir.resolve("pom.xml").createFile()
    }

    private fun setupBspConnection(serverName: String) {
        val bspDir = tempDir.resolve(".bsp").createDirectories()
        bspDir.resolve("$serverName.json").writeText(
            """
            {
                "name": "$serverName",
                "version": "1.0.0",
                "bspVersion": "2.1.0",
                "languages": ["java", "groovy"],
                "argv": ["$serverName"]
            }
            """.trimIndent(),
        )
    }

    // -------------------------------------------------------------------------
    // Test Doubles - implement marker interfaces for type-safe filterIsInstance
    // -------------------------------------------------------------------------

    /**
     * Fake BuildTool for non-specialized build tools (e.g., Maven).
     */
    private class FakeBuildTool(override val name: String, private val canHandleCheck: (Path) -> Boolean) : BuildTool {
        override fun canHandle(workspaceRoot: Path): Boolean = canHandleCheck(workspaceRoot)
        override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution =
            WorkspaceResolution.empty()
    }

    /**
     * Fake BSP build tool implementing marker interface.
     * filterIsInstance<BspCompatibleBuildTool> will match this.
     */
    private class FakeBspBuildTool(private val canHandleCheck: (Path) -> Boolean) : BspCompatibleBuildTool {
        override val name: String = "BSP"
        override fun canHandle(workspaceRoot: Path): Boolean = canHandleCheck(workspaceRoot)
        override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution =
            WorkspaceResolution.empty()
    }

    /**
     * Fake native Gradle build tool implementing marker interface.
     * filterIsInstance<NativeGradleBuildTool> will match this.
     */
    private class FakeGradleBuildTool(private val canHandleCheck: (Path) -> Boolean) : NativeGradleBuildTool {
        override val name: String = "Gradle"
        override fun canHandle(workspaceRoot: Path): Boolean = canHandleCheck(workspaceRoot)
        override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution =
            WorkspaceResolution.empty()
    }
}
