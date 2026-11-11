package com.github.albertocavalcante.gvy.build.gradle

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Creating a Tooling API connection is lazy (no daemon starts), so these run without Gradle. */
class GradleConnectionPoolShutdownTest {

    @TempDir
    lateinit var projectDir: Path

    @AfterEach
    fun reopen() {
        GradleConnectionPool.reopenForTesting()
    }

    @Test
    fun `after shutdown getConnection is rejected`() {
        GradleConnectionPool.shutdown()

        assertThrows(IllegalStateException::class.java) { GradleConnectionPool.getConnection(projectDir, null) }
        assertEquals(0, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `after shutdown acquire retains nothing and closing the lease is safe`() {
        GradleConnectionPool.shutdown()

        GradleConnectionPool.acquire(projectDir, null).use { lease ->
            assertEquals(0, GradleConnectionPool.getActiveConnectionCount(), "the pool must not track it")
            lease.connection // usable for the operation
        }

        assertEquals(0, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `before shutdown acquire pools the connection and the lease returns it`() {
        GradleConnectionPool.acquire(projectDir, null).use {
            assertEquals(1, GradleConnectionPool.getActiveConnectionCount())
        }

        assertEquals(1, GradleConnectionPool.getActiveConnectionCount())
        GradleConnectionPool.shutdown()
        assertEquals(0, GradleConnectionPool.getActiveConnectionCount())
    }
}
