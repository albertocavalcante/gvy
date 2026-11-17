// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import com.github.albertocavalcante.gvy.build.ResolutionCancellation
import io.mockk.every
import io.mockk.mockk
import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.idea.IdeaProject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dependency tree must be cancellable and use the same isolated-home fallback as a resolution. */
class GradleDependencyMetadataCancellationTest {

    private class Fixture(projectDir: Path, private val onGet: Fixture.(Int) -> IdeaProject) {
        val tokens = CopyOnWriteArrayList<CancellationToken>()
        val homes = CopyOnWriteArrayList<File?>()
        val released = AtomicInteger()
        private val calls = AtomicInteger()

        val factory = object : LeasingConnectionFactory {
            override fun getConnection(projectDir: Path, gradleUserHomeDir: File?): ProjectConnection =
                error("the dependency tree must lease the connection")

            override fun acquire(projectDir: Path, gradleUserHomeDir: File?): ConnectionLease {
                homes.add(gradleUserHomeDir)
                return ConnectionLease(connection) { released.incrementAndGet() }
            }
        }

        private val connection: ProjectConnection = mockk()

        init {
            projectDir.resolve("build.gradle").toFile().writeText("plugins { id 'java' }")
            val builder = mockk<ModelBuilder<IdeaProject>>()
            every { builder.withCancellationToken(any()) } answers {
                tokens.add(firstArg())
                builder
            }
            every { builder.withArguments(*anyVararg()) } returns builder
            every { builder.setJvmArguments(*anyVararg<String>()) } returns builder
            every { builder.setJavaHome(any()) } returns builder
            every { builder.get() } answers { this@Fixture.onGet(calls.incrementAndGet()) }
            every { connection.model(IdeaProject::class.java) } returns builder
        }
    }

    @Test
    fun `cancelling a blocked dependency tree fetch cancels the token and releases the lease`(@TempDir dir: Path) {
        val fetching = CountDownLatch(1)
        val fixture = Fixture(dir) {
            fetching.countDown()
            val token = tokens.last()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS)
            while (!token.isCancellationRequested && System.nanoTime() < deadline) Thread.onSpinWait()
            throw BuildCancelledException("Build cancelled.")
        }
        val cancellation = ResolutionCancellation()
        val tool = GradleBuildTool(connectionFactory = fixture.factory)

        val result = CompletableFuture.supplyAsync { tool.getDependencyMetadata(dir, cancellation) }
        assertTrue(fetching.await(BOUND_SECONDS, TimeUnit.SECONDS), "Fetch never started")
        cancellation.cancel()

        val failure = assertThrows<ExecutionException> { result.get(BOUND_SECONDS, TimeUnit.SECONDS) }
        assertIs<CancellationException>(failure.cause, "Cancellation must not be swallowed into an empty tree")
        assertTrue(fixture.tokens.single().isCancellationRequested)
        assertEquals(1, fixture.released.get(), "the lease must be released")
        assertEquals(1, fixture.homes.size, "a cancelled fetch must not fall back to an isolated home")
    }

    @Test
    fun `an init script failure retries the dependency tree in an isolated Gradle user home`(@TempDir dir: Path) {
        val fixture = Fixture(dir) { call ->
            if (call == 1) {
                throw RuntimeException(
                    "Could not open cp_init generic class cache for initialization script '~/.gradle/init.d/x.gradle'",
                )
            }
            mockk<IdeaProject>(relaxed = true)
        }

        // Keep the isolated home inside the test's temp dir instead of the real user home.
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", dir.toString())
        try {
            GradleBuildTool(connectionFactory = fixture.factory).getDependencyMetadata(dir)
        } finally {
            System.setProperty("user.home", originalHome)
        }

        assertEquals(2, fixture.homes.size)
        assertNull(fixture.homes[0], "first attempt uses the default Gradle user home")
        val isolated = assertNotNull(fixture.homes[1], "retry must use an isolated Gradle user home")
        assertEquals(dir.resolve(".groovy-lsp").resolve("gradle-user-home").toFile(), isolated)
        assertEquals(2, fixture.released.get(), "both leases must be released")
    }

    private companion object {
        const val BOUND_SECONDS = 10L
    }
}
