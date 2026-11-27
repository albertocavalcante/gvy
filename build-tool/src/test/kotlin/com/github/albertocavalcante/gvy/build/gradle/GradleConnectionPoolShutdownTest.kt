// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import io.mockk.every
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val WAIT_SECONDS = 10L

/** Each test shuts down a pool of its own, with fake connections, and leaves [GradleConnectionPool] alone. */
class GradleConnectionPoolShutdownTest {

    @TempDir
    lateinit var projectDir: Path

    private val pool = ProjectConnectionPool(connect = { _, _ -> mockk<ProjectConnection>(relaxed = true) })

    /**
     * A Gradle user home whose toPath() parks until [release] opens. The pool calls it to build the cache key on the
     * way to the cache, holding no lock, so a call parked there is one preempted after it began, and the test can run
     * the whole of shutdown before it resumes.
     */
    private class ParkingUserHome(path: Path) : File(path.toString()) {
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun toPath(): Path {
            parked.countDown()
            check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "The call was never released" }
            return super.toPath()
        }
    }

    @AfterEach
    fun shutDown() {
        pool.shutdown()
    }

    @Test
    fun `after shutdown getConnection is rejected`() {
        pool.shutdown()

        val failure = assertThrows(IllegalStateException::class.java) { pool.getConnection(projectDir, null) }
        assertEquals("Gradle connection pool is shut down", failure.message)
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
        val userHome = ParkingUserHome(projectDir.resolve("gradle-home"))
        val resolution = Executors.newSingleThreadExecutor()
        try {
            val acquired = resolution.submit(Callable { pool.acquire(projectDir, userHome) })
            assertTrue(userHome.parked.await(WAIT_SECONDS, TimeUnit.SECONDS), "acquire never reached the cache key")

            pool.shutdown()
            userHome.release.countDown()
            val lease = acquired.get(WAIT_SECONDS, TimeUnit.SECONDS)
            val connection = lease.connection

            assertEquals(0, pool.getActiveConnectionCount(), "a pool that is shut down must not keep the connection")
            assertFalse(pool.isSweeping, "a pool that is shut down must not restart its sweeper")
            lease.close()
            verify(exactly = 1) { connection.close() }
        } finally {
            userHome.release.countDown()
            resolution.shutdownNow()
        }
    }

    @Test
    fun `a getConnection that shutdown overtakes is rejected and pools nothing`() {
        val userHome = ParkingUserHome(projectDir.resolve("gradle-home"))
        val resolution = Executors.newSingleThreadExecutor()
        try {
            val connected = resolution.submit(Callable { pool.getConnection(projectDir, userHome) })
            assertTrue(userHome.parked.await(WAIT_SECONDS, TimeUnit.SECONDS), "getConnection never reached the key")

            pool.shutdown()
            userHome.release.countDown()

            val failure = assertThrows(ExecutionException::class.java) { connected.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalStateException, "expected the shut-down rejection, got ${failure.cause}")
            assertEquals("Gradle connection pool is shut down", failure.cause?.message)
            assertEquals(0, pool.getActiveConnectionCount(), "a pool that is shut down must not keep the connection")
            assertFalse(pool.isSweeping, "a pool that is shut down must not restart its sweeper")
        } finally {
            userHome.release.countDown()
            resolution.shutdownNow()
        }
    }

    @Test
    fun `shutdown stops the sweeper before it closes the connections`() {
        val sweepingWhileClosing = mutableListOf<Boolean>()
        lateinit var observed: ProjectConnectionPool
        observed = ProjectConnectionPool(
            connect = { _, _ ->
                mockk<ProjectConnection>(relaxed = true).also { connection ->
                    every { connection.close() } answers { sweepingWhileClosing += observed.isSweeping }
                }
            },
        )
        observed.acquire(projectDir, null).close()
        assertTrue(observed.isSweeping)

        // A close can block on a connection still in use; the sweep must not outlive shutdown's wait for it.
        observed.shutdown()

        assertEquals(listOf(false), sweepingWhileClosing)
        assertFalse(observed.isSweeping)
    }
}
