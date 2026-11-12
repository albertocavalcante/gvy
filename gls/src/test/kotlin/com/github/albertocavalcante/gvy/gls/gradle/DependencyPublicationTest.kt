package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildTool
import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyPublicationTest {
    @Test
    fun `readiness waits until completion callback publishes workspace state`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val buildToolManager = mockk<BuildToolManager> {
            coEvery { detectBuildTool(any()) } returns null
        }
        val manager = DependencyManager(buildToolManager, CoroutineScope(Dispatchers.Default))
        manager.startAsyncResolution(
            Paths.get("/tmp/test-workspace"),
            onComplete = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            },
            enableFileWatching = false,
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(manager.isDependenciesReady(), "workspace publication is still held")
        } finally {
            release.countDown()
            manager.cancel()
        }
    }

    @Test
    fun `a replacement started by completion cannot be marked complete by the old attempt`() {
        val secondEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRoot = Paths.get("/tmp/first-workspace")
        val secondRoot = Paths.get("/tmp/second-workspace")
        val tool = mockk<BuildTool> {
            coEvery { name } returns "test"
            coEvery { resolve(any(), any()) } coAnswers {
                if (firstArg<java.nio.file.Path>() == secondRoot) {
                    secondEntered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                WorkspaceResolution.empty()
            }
        }
        val manager = DependencyManager(
            mockk<BuildToolManager> { coEvery { detectBuildTool(any()) } returns tool },
            CoroutineScope(Dispatchers.Default),
        )
        manager.startAsyncResolution(firstRoot, onComplete = {
            manager.startAsyncResolution(secondRoot, onComplete = {}, enableFileWatching = false)
        }, enableFileWatching = false)
        try {
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            assertEquals(DependencyManager.State.IN_PROGRESS, manager.getState())
            assertFalse(manager.isDependenciesReady())
        } finally {
            release.countDown()
            manager.cancel()
        }
    }
}
