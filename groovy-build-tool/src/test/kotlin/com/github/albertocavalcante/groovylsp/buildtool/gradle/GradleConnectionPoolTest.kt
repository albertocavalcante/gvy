package com.github.albertocavalcante.groovylsp.buildtool.gradle

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GradleConnectionPoolTest {

    @TempDir
    lateinit var tempDir: Path

    @org.junit.jupiter.api.BeforeEach
    fun setup() {
        GradleConnectionPool.shutdown()
    }

    @AfterEach
    fun cleanup() {
        GradleConnectionPool.shutdown()
    }

    @Test
    fun `getConnection returns same instance for same project`() {
        val projectDir = tempDir.resolve("project1")
        projectDir.toFile().mkdirs()

        val conn1 = GradleConnectionPool.getConnection(projectDir, null)
        val conn2 = GradleConnectionPool.getConnection(projectDir, null)

        assertSame(conn1, conn2, "Should return same connection instance for same project")
        assertEquals(1, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `getConnection returns different instances for different projects`() {
        val project1 = tempDir.resolve("project1")
        val project2 = tempDir.resolve("project2")
        project1.toFile().mkdirs()
        project2.toFile().mkdirs()

        val conn1 = GradleConnectionPool.getConnection(project1, null)
        val conn2 = GradleConnectionPool.getConnection(project2, null)

        assertNotSame(conn1, conn2, "Should return different instances for different projects")
        assertEquals(2, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `getConnection differentiates by user home`() {
        val projectDir = tempDir.resolve("project1")
        projectDir.toFile().mkdirs()
        val userHome1 = tempDir.resolve("home1").toFile()
        val userHome2 = tempDir.resolve("home2").toFile()

        val conn1 = GradleConnectionPool.getConnection(projectDir, userHome1)
        val conn2 = GradleConnectionPool.getConnection(projectDir, userHome2)
        val conn3 = GradleConnectionPool.getConnection(projectDir, null) // Default home

        assertNotSame(conn1, conn2, "Should differentiate by user home 1 vs 2")
        assertNotSame(conn1, conn3, "Should differentiate by user home 1 vs default")
        assertEquals(3, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `closeConnection removes connection from pool`() {
        val projectDir = tempDir.resolve("project1")
        projectDir.toFile().mkdirs()

        GradleConnectionPool.getConnection(projectDir, null)
        assertEquals(1, GradleConnectionPool.getActiveConnectionCount())

        GradleConnectionPool.closeConnection(projectDir)
        assertEquals(0, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `closeConnection removes all variants for project`() {
        val projectDir = tempDir.resolve("project1")
        projectDir.toFile().mkdirs()
        val userHome = tempDir.resolve("home1").toFile()

        GradleConnectionPool.getConnection(projectDir, null)
        GradleConnectionPool.getConnection(projectDir, userHome)
        assertEquals(2, GradleConnectionPool.getActiveConnectionCount())

        // Should close both
        GradleConnectionPool.closeConnection(projectDir)
        assertEquals(0, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `shutdown clears all connections`() {
        val project1 = tempDir.resolve("project1")
        val project2 = tempDir.resolve("project2")
        project1.toFile().mkdirs()
        project2.toFile().mkdirs()

        GradleConnectionPool.getConnection(project1, null)
        GradleConnectionPool.getConnection(project2, null)
        assertEquals(2, GradleConnectionPool.getActiveConnectionCount())

        GradleConnectionPool.shutdown()
        assertEquals(0, GradleConnectionPool.getActiveConnectionCount())
    }

    @Test
    fun `getStats returns valid string`() {
        val projectDir = tempDir.resolve("project1")
        projectDir.toFile().mkdirs()
        GradleConnectionPool.getConnection(projectDir, null)

        val stats = GradleConnectionPool.getStats()
        assertTrue(stats.contains("connections=1"))
        assertTrue(stats.contains("project1"))
    }
}
