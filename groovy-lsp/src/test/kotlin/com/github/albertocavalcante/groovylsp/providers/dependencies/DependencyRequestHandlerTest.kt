package com.github.albertocavalcante.groovylsp.providers.dependencies

import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleBuildTool
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleConnectionFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyRequestHandlerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var buildToolManagerProvider: () -> BuildToolManager?
    private lateinit var workspaceRootProvider: () -> Path?
    private lateinit var connectionFactory: GradleConnectionFactory
    private lateinit var handler: DependencyRequestHandler

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        buildToolManagerProvider = { null }
        workspaceRootProvider = { null }
        connectionFactory = mockk()
        handler = DependencyRequestHandler(
            coroutineScope = testScope,
            buildToolManagerProvider = buildToolManagerProvider,
            workspaceRootProvider = workspaceRootProvider,
            connectionFactory = connectionFactory,
            ioDispatcher = testDispatcher,
        )
    }

    @Test
    fun `getDependencies returns empty when no workspace root found`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file:///nonexistent/workspace")
        buildToolManagerProvider = { null }
        workspaceRootProvider = { null }
        handler = DependencyRequestHandler(
            coroutineScope = testScope,
            buildToolManagerProvider = buildToolManagerProvider,
            workspaceRootProvider = workspaceRootProvider,
            connectionFactory = connectionFactory,
            ioDispatcher = testDispatcher,
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
    fun `getDependencies returns empty when build tool manager is null`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file://$tempDir")
        buildToolManagerProvider = { null }
        workspaceRootProvider = { tempDir }
        handler = DependencyRequestHandler(
            coroutineScope = testScope,
            buildToolManagerProvider = buildToolManagerProvider,
            workspaceRootProvider = workspaceRootProvider,
            connectionFactory = connectionFactory,
            ioDispatcher = testDispatcher,
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
    fun `getDependencies returns empty when no build tool detected`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file://$tempDir")
        val mockBuildToolManager = mockk<BuildToolManager>()
        every { mockBuildToolManager.detectBuildTool(any<Path>()) } returns null

        buildToolManagerProvider = { mockBuildToolManager }
        workspaceRootProvider = { tempDir }
        handler = DependencyRequestHandler(
            coroutineScope = testScope,
            buildToolManagerProvider = buildToolManagerProvider,
            workspaceRootProvider = workspaceRootProvider,
            connectionFactory = connectionFactory,
            ioDispatcher = testDispatcher,
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
    fun `getDependencies returns empty for non-Gradle build tool`() = runTest(testDispatcher) {
        // Given
        val params = GetDependenciesParams("file://$tempDir")
        val mockBuildTool = mockk<BuildTool>()
        every { mockBuildTool.name } returns "Maven"

        val mockBuildToolManager = mockk<BuildToolManager>()
        every { mockBuildToolManager.detectBuildTool(any<Path>()) } returns mockBuildTool

        buildToolManagerProvider = { mockBuildToolManager }
        workspaceRootProvider = { tempDir }
        handler = DependencyRequestHandler(
            coroutineScope = testScope,
            buildToolManagerProvider = buildToolManagerProvider,
            workspaceRootProvider = workspaceRootProvider,
            connectionFactory = connectionFactory,
            ioDispatcher = testDispatcher,
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

        buildToolManagerProvider = { mockBuildToolManager }
        workspaceRootProvider = { null } // Should resolve from params
        handler = DependencyRequestHandler(
            coroutineScope = testScope,
            buildToolManagerProvider = buildToolManagerProvider,
            workspaceRootProvider = workspaceRootProvider,
            connectionFactory = connectionFactory,
            ioDispatcher = testDispatcher,
        )

        // When - use the future and advance virtual time
        val future = handler.getDependencies(params)
        testScope.testScheduler.advanceUntilIdle()
        val result = future.get()

        // Then
        assertEquals(emptyList(), result.dependencies)
        assertEquals("unknown", result.buildTool)
    }

    @Test
    fun `parseJarFileName extracts name and version correctly from standard format`() {
        // Test commons-lang3-3.12.0.jar
        val result1 = parseJarFileNameHelper("commons-lang3-3.12.0.jar")
        assertEquals("commons-lang3", result1.first)
        assertEquals("3.12.0", result1.second)

        // Test junit-4.13.jar
        val result2 = parseJarFileNameHelper("junit-4.13.jar")
        assertEquals("junit", result2.first)
        assertEquals("4.13", result2.second)

        // Test groovy-all-2.5.14.jar
        val result3 = parseJarFileNameHelper("groovy-all-2.5.14.jar")
        assertEquals("groovy-all", result3.first)
        assertEquals("2.5.14", result3.second)
    }

    @Test
    fun `parseJarFileName handles jar without version`() {
        val result = parseJarFileNameHelper("some-library.jar")
        assertEquals("some-library", result.first)
        assertEquals("unknown", result.second)
    }

    @Test
    fun `parseJarFileName handles complex version strings`() {
        // Test with snapshot version
        val result1 = parseJarFileNameHelper("my-lib-1.0.0-SNAPSHOT.jar")
        assertEquals("my-lib", result1.first)
        assertEquals("1.0.0-SNAPSHOT", result1.second)

        // Test with build metadata
        val result2 = parseJarFileNameHelper("library-2.3.4-beta.1.jar")
        assertEquals("library", result2.first)
        assertEquals("2.3.4-beta.1", result2.second)
    }

    @Test
    fun `parseJarFileName handles artifact with multiple dashes in name`() {
        val result = parseJarFileNameHelper("spring-boot-starter-web-3.0.0.jar")
        assertEquals("spring-boot-starter-web", result.first)
        assertEquals("3.0.0", result.second)
    }

    /**
     * Helper method to test the private parseJarFileName method.
     * This uses reflection to access the private method.
     */
    private fun parseJarFileNameHelper(fileName: String): Pair<String, String> {
        val method = DependencyRequestHandler::class.java.getDeclaredMethod(
            "parseJarFileName",
            String::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(handler, fileName) as Pair<String, String>
    }
}
