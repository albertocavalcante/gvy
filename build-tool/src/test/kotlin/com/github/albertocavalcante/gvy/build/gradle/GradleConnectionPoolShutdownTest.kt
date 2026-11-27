// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import io.mockk.mockk
import io.mockk.verify
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val WAIT_SECONDS = 10L

/** Each test shuts down a pool of its own, with fake connections, and leaves [GradleConnectionPool] alone. */
class GradleConnectionPoolShutdownTest {

    @TempDir
    lateinit var projectDir: Path

    private val pool = ProjectConnectionPool(connect = { _, _ -> mockk<ProjectConnection>(relaxed = true) })

    @AfterEach
    fun shutDown() {
        pool.shutdown()
    }

    @Test
    fun `after shutdown getConnection is rejected`() {
        pool.shutdown()

        assertThrows(IllegalStateException::class.java) { pool.getConnection(projectDir, null) }
        assertEquals(0, pool.getActiveConnectionCount())
        assertFalse(pool.isSweeping, "a pool that is shut down must not restart its sweeper")
    }

    @Test
    fun `after shutdown acquire retains nothing and closing the lease closes the connection`() {
        pool.shutdown()

        val lease = pool.acquire(projectDir, null)
        val connection = lease.connection
        assertEquals(0, pool.getActiveConnectionCount(), "the pool must not track it")
        assertFalse(pool.isSweeping, "a pool that is shut down must not restart its sweeper")
        verify(exactly = 0) { connection.close() }

        lease.close()

        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `before shutdown acquire pools the connection and the lease returns it`() {
        val lease = pool.acquire(projectDir, null)
        val connection = lease.connection
        assertEquals(1, pool.getActiveConnectionCount())
        assertTrue(pool.isSweeping)

        lease.close()

        assertEquals(1, pool.getActiveConnectionCount())
        verify(exactly = 0) { connection.close() }

        pool.shutdown()

        assertEquals(0, pool.getActiveConnectionCount())
        assertFalse(pool.isSweeping)
        verify(exactly = 1) { connection.close() }
    }

    @Test
    fun `an acquire that shutdown overtakes neither stays pooled nor stays open`() {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)

        // acquire turns the user home into a cache key before it reaches the cache, holding no lock. Parking there
        // is a resolution thread preempted once acquire has begun, with all of shutdown running before it resumes.
        val userHome = object : File(projectDir.resolve("gradle-home").toString()) {
            override fun toPath(): Path {
                parked.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "acquire was never released" }
                return super.toPath()
            }
        }
        val resolution = Executors.newSingleThreadExecutor()
        try {
            val acquired = resolution.submit(Callable { pool.acquire(projectDir, userHome) })
            assertTrue(parked.await(WAIT_SECONDS, TimeUnit.SECONDS), "acquire never reached the cache key")

            pool.shutdown()
            release.countDown()
            val lease = acquired.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val connection = lease.connection

            assertEquals(0, pool.getActiveConnectionCount(), "a pool that is shut down must not keep the connection")
            assertFalse(pool.isSweeping, "a pool that is shut down must not restart its sweeper")
            lease.close()
            verify(exactly = 1) { connection.close() }
        } finally {
            release.countDown()
            resolution.shutdownNow()
        }
    }
}
