package com.github.albertocavalcante.groovylsp.gradle

import com.github.albertocavalcante.groovylsp.buildtool.BuildTool
import com.github.albertocavalcante.groovylsp.buildtool.BuildToolManager
import com.github.albertocavalcante.groovylsp.buildtool.ResolutionCodes
import com.github.albertocavalcante.groovylsp.buildtool.ResolutionStatus
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DependencyManagerTest {

    private val testWorkspaceRoot = Paths.get("/tmp/test-workspace")

    @Test
    fun `should classify toolchain exception and create failed resolution`() = runBlocking {
        val toolchainError = RuntimeException(
            "Cannot find a Java installation on your machine (Mac OS X 15.6 aarch64) matching: " +
                "{languageVersion=17, vendor=any vendor}",
        )

        val mockBuildTool = mockk<BuildTool> {
            coEvery { name } returns "Gradle"
            coEvery { resolve(any(), any()) } throws toolchainError
        }

        val buildToolManager = mockk<BuildToolManager> {
            coEvery { detectBuildTool(any()) } returns mockBuildTool
        }

        val dependencyManager = DependencyManager(buildToolManager, CoroutineScope(Dispatchers.Unconfined))

        val latch = CountDownLatch(1)
        var capturedResolution: WorkspaceResolution? = null

        dependencyManager.startAsyncResolution(
            workspaceRoot = testWorkspaceRoot,
            onComplete = { resolution ->
                capturedResolution = resolution
                latch.countDown()
            },
            onError = { error ->
                // Should NOT be called for classified errors
                throw AssertionError("onError should not be called for classified errors: ${error.message}")
            },
            enableFileWatching = false,
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Completion callback should be called")
        assertNotNull(capturedResolution)

        val status = capturedResolution!!.status
        assertTrue(status is ResolutionStatus.Failed, "Status should be Failed")

        val failedStatus = status as ResolutionStatus.Failed
        assertEquals(
            ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED,
            failedStatus.code,
            "Should classify as toolchain provisioning error",
        )
        assertTrue(failedStatus.message.contains("Java 17"), "Message should mention Java 17")
    }

    @Test
    fun `should call onComplete with failed status instead of onError for classified errors`() = runBlocking {
        val jdkMismatchError = RuntimeException("Unsupported class file major version 65")

        val mockBuildTool = mockk<BuildTool> {
            coEvery { name } returns "Gradle"
            coEvery { resolve(any(), any()) } throws jdkMismatchError
        }

        val buildToolManager = mockk<BuildToolManager> {
            coEvery { detectBuildTool(any()) } returns mockBuildTool
        }

        val dependencyManager = DependencyManager(buildToolManager, CoroutineScope(Dispatchers.Unconfined))

        val completeLatch = CountDownLatch(1)
        val errorLatch = CountDownLatch(1)
        var onErrorCalled = false

        dependencyManager.startAsyncResolution(
            workspaceRoot = testWorkspaceRoot,
            onComplete = { resolution ->
                assertTrue(resolution.status is ResolutionStatus.Failed)
                assertEquals(
                    ResolutionCodes.GRADLE_JDK_INCOMPATIBLE,
                    (resolution.status as ResolutionStatus.Failed).code,
                )
                completeLatch.countDown()
            },
            onError = {
                onErrorCalled = true
                errorLatch.countDown()
            },
            enableFileWatching = false,
        )

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "onComplete should be called")

        // Give a moment for onError to potentially be called (it shouldn't be)
        errorLatch.await(500, TimeUnit.MILLISECONDS)

        assertFalse(onErrorCalled, "onError should NOT be called for classified errors")
    }

    @Test
    fun `should call onError for truly unexpected errors`() = runBlocking {
        val unexpectedError = NullPointerException("Something went really wrong")

        val buildToolManager = mockk<BuildToolManager> {
            coEvery { detectBuildTool(any()) } throws unexpectedError
        }

        val dependencyManager = DependencyManager(buildToolManager, CoroutineScope(Dispatchers.Unconfined))

        val latch = CountDownLatch(1)
        var onErrorCalled = false

        dependencyManager.startAsyncResolution(
            workspaceRoot = testWorkspaceRoot,
            onComplete = {
                // Should not be called for unexpected errors
                throw AssertionError("onComplete should not be called for unexpected errors")
            },
            onError = { error ->
                onErrorCalled = true
                assertEquals(unexpectedError, error)
                latch.countDown()
            },
            enableFileWatching = false,
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS), "onError should be called")
        assertTrue(onErrorCalled, "onError should be called for unexpected errors")
    }
}
