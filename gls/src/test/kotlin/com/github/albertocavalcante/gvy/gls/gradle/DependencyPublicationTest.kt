package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildToolManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}
