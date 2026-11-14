// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.gradle

import com.github.albertocavalcante.gvy.build.BuildToolManager
import com.github.albertocavalcante.gvy.build.gradle.GradleBuildTool
import com.github.albertocavalcante.gvy.build.gradle.GradleConnectionFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleCallerCancellationTest {
    @Test
    fun `manager cancellation reaches tooling token without retry`(@TempDir root: Path) {
        root.resolve("build.gradle").toFile().writeText("plugins { id 'java' }")
        val fetching = CountDownLatch(1)
        val modelCalls = AtomicInteger()
        val connections = AtomicInteger()
        val callbacks = AtomicInteger()
        lateinit var token: CancellationToken
        val idea = mockk<ModelBuilder<IdeaProject>>()
        every { idea.withCancellationToken(any()) } answers {
            token = firstArg()
            idea
        }
        every { idea.withArguments(*anyVararg()) } returns idea
        every { idea.setJvmArguments(any(), any()) } returns idea
        every { idea.get() } answers {
            modelCalls.incrementAndGet()
            fetching.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!token.isCancellationRequested && System.nanoTime() < deadline) Thread.onSpinWait()
            throw BuildCancelledException("Build cancelled")
        }
        val environment = mockk<ModelBuilder<BuildEnvironment>>(relaxed = true)
        every { environment.withCancellationToken(any()) } returns environment
        every { environment.withArguments(*anyVararg()) } returns environment
        every { environment.setJvmArguments(*anyVararg()) } returns environment
        val buildEnvironment = mockk<BuildEnvironment>(relaxed = true)
        every { buildEnvironment.gradle.gradleVersion } returns "8.5"
        every { environment.get() } returns buildEnvironment
        val connection = mockk<ProjectConnection>()
        every { connection.model(BuildEnvironment::class.java) } returns environment
        every { connection.model(IdeaProject::class.java) } returns idea
        val factory = GradleConnectionFactory { _, _ ->
            connections.incrementAndGet()
            connection
        }
        val tools = mockk<BuildToolManager>()
        every { tools.detectBuildTool(root) } returns GradleBuildTool(connectionFactory = factory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = DependencyManager(tools, scope)
        try {
            manager.startAsyncResolution(
                root,
                onComplete = { callbacks.incrementAndGet() },
                onError = { callbacks.incrementAndGet() },
                enableFileWatching = false,
            )
            assertTrue(fetching.await(5, TimeUnit.SECONDS))
            val job = scope.coroutineContext[Job]!!.children.single()
            manager.cancel()
            runBlocking { withTimeout(5_000) { job.join() } }
            assertTrue(token.isCancellationRequested)
            assertEquals(1, modelCalls.get())
            assertEquals(1, connections.get())
            assertEquals(0, callbacks.get())
        } finally {
            manager.cancel()
            scope.cancel()
        }
    }
}
