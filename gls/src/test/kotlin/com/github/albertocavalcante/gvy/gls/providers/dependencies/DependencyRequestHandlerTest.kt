package com.github.albertocavalcante.gvy.gls.providers.dependencies

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.DependencyMetadata
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class DependencyRequestHandlerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @TempDir
    lateinit var tempDir: Path

    /**
     * Helper method to create a handler with mocked dependencies.
     */
    private fun createHandlerWithMocks(
        buildToolManager: BuildToolManager? = null,
        workspaceRoot: Path? = null,
    ): DependencyRequestHandler = DependencyRequestHandler(
        coroutineScope = testScope,
        buildToolManagerProvider = { buildToolManager },
        workspaceRootProvider = { workspaceRoot },
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `getDependencies returns empty when no workspace root found`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file:///nonexistent/workspace")
        val handler = createHandlerWithMocks()

        // When
        val future = handler.getDependencies(params)
        testScope.testScheduler.advanceUntilIdle()
        val result = future.get()

        // Then
        assertEquals(emptyList(), result.dependencies)
        assertEquals("unknown", result.buildTool)
    }

    @Test
    fun `getDependencies returns empty when build tool manager is null`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file://$tempDir")
        val handler = createHandlerWithMocks(workspaceRoot = tempDir)

        // When
        val future = handler.getDependencies(params)
        testScope.testScheduler.advanceUntilIdle()
        val result = future.get()

        // Then
        assertEquals(emptyList(), result.dependencies)
        assertEquals("unknown", result.buildTool)
    }

    @Test
    fun `getDependencies returns empty when no build tool detected`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file://$tempDir")
        val mockBuildToolManager = mockk<BuildToolManager>()
        every { mockBuildToolManager.detectBuildTool(any<Path>()) } returns null

        val handler = createHandlerWithMocks(
            buildToolManager = mockBuildToolManager,
            workspaceRoot = tempDir,
        )

        // When
        val future = handler.getDependencies(params)
        testScope.testScheduler.advanceUntilIdle()
        val result = future.get()

        // Then
        assertEquals(emptyList(), result.dependencies)
        assertEquals("unknown", result.buildTool)
    }

    @Test
    fun `getDependencies returns empty when build tool does not support metadata extraction`() =
        runTest(testDispatcher) {
            // Given
            val params = GetDependenciesParams("file://$tempDir")
            val mockBuildTool = mockk<BuildTool>()
            every { mockBuildTool.name } returns "Maven"
            every { mockBuildTool.getDependencyMetadata(any()) } returns null

            val mockBuildToolManager = mockk<BuildToolManager>()
            every { mockBuildToolManager.detectBuildTool(any<Path>()) } returns mockBuildTool

            val handler = createHandlerWithMocks(
                buildToolManager = mockBuildToolManager,
                workspaceRoot = tempDir,
            )

            // When
            val future = handler.getDependencies(params)
            testScope.testScheduler.advanceUntilIdle()
            val result = future.get()

            // Then
            assertEquals(emptyList(), result.dependencies)
            assertEquals("maven", result.buildTool)
        }

    @Test
    fun `getDependencies resolves workspace URI correctly`() = runTest(testDispatcher) {
        // Given - workspace URI with file:// prefix
        val params = GetDependenciesParams("file://$tempDir")
        val mockBuildToolManager = mockk<BuildToolManager>()
        every { mockBuildToolManager.detectBuildTool(any<Path>()) } returns null

        val handler = createHandlerWithMocks(buildToolManager = mockBuildToolManager)

        // When - use the future and advance virtual time
        val future = handler.getDependencies(params)
        testScope.testScheduler.advanceUntilIdle()
        val result = future.get()

        // Then
        assertEquals(emptyList(), result.dependencies)
        assertEquals("unknown", result.buildTool)
    }

    @Test
    fun `getDependencies returns dependencies from build tool`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file://$tempDir")
        val mockMetadata = listOf(
            DependencyMetadata(
                name = "org.apache.commons:commons-lang3",
                version = "3.12.0",
                scope = "compile",
                path = "file:///path/to/commons-lang3-3.12.0.jar",
                isTransitive = false,
            ),
            DependencyMetadata(
                name = "junit:junit",
                version = "4.13.2",
                scope = "test",
                path = "file:///path/to/junit-4.13.2.jar",
                isTransitive = true,
            ),
        )

        val mockBuildTool = mockk<BuildTool>()
        every { mockBuildTool.name } returns "Gradle"
        every { mockBuildTool.getDependencyMetadata(any()) } returns mockMetadata

        val mockBuildToolManager = mockk<BuildToolManager>()
        every { mockBuildToolManager.detectBuildTool(any<Path>()) } returns mockBuildTool

        val handler = createHandlerWithMocks(
            buildToolManager = mockBuildToolManager,
            workspaceRoot = tempDir,
        )

        // When
        val future = handler.getDependencies(params)
        testScope.testScheduler.advanceUntilIdle()
        val result = future.get()

        // Then
        assertEquals(2, result.dependencies.size)
        assertEquals("gradle", result.buildTool)

        val dep1 = result.dependencies[0]
        assertEquals("org.apache.commons:commons-lang3", dep1.name)
        assertEquals("3.12.0", dep1.version)
        assertEquals("compile", dep1.scope)
        assertEquals("file:///path/to/commons-lang3-3.12.0.jar", dep1.path)
        assertEquals(false, dep1.isTransitive)

        val dep2 = result.dependencies[1]
        assertEquals("junit:junit", dep2.name)
        assertEquals("4.13.2", dep2.version)
        assertEquals("test", dep2.scope)
        assertEquals("file:///path/to/junit-4.13.2.jar", dep2.path)
        assertEquals(true, dep2.isTransitive)
    }
}
