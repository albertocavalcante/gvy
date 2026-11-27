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
import java.nio.file.Path

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
}
