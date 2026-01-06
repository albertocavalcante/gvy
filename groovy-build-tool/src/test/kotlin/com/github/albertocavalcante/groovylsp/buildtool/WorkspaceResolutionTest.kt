package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceResolutionTest {

    @Test
    fun `empty resolution has Success status and is usable`() {
        val resolution = WorkspaceResolution.empty()

        assertEquals(ResolutionStatus.Success, resolution.status)
        assertTrue(resolution.isUsable)
        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())
    }

    @Test
    fun `failed factory creates Failed status`() {
        val resolution = WorkspaceResolution.failed(
            code = "TOOLCHAIN_PROVISIONING_FAILED",
            message = "Failed to provision toolchain",
        )

        assertTrue(resolution.status is ResolutionStatus.Failed)
        assertFalse(resolution.isUsable)
        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())

        val failedStatus = resolution.status
        assertEquals("TOOLCHAIN_PROVISIONING_FAILED", failedStatus.code)
        assertEquals("Failed to provision toolchain", failedStatus.message)
    }

    @Test
    fun `failed factory with cause creates Failed status with cause`() {
        val cause = RuntimeException("Root cause")
        val resolution = WorkspaceResolution.failed(
            code = "DEPENDENCY_RESOLUTION_FAILED",
            message = "Resolution failed",
            cause = cause,
        )

        assertTrue(resolution.status is ResolutionStatus.Failed)
        assertFalse(resolution.isUsable)

        val failedStatus = resolution.status
        assertEquals(cause, failedStatus.cause)
    }

    @Test
    fun `resolution with Warning status is usable`() {
        val resolution = WorkspaceResolution(
            dependencies = emptyList(),
            sourceDirectories = emptyList(),
            status = ResolutionStatus.Warning("ZERO_DEPENDENCIES", "No dependencies found"),
        )

        assertTrue(resolution.status is ResolutionStatus.Warning)
        assertTrue(resolution.isUsable)
    }

    @Test
    fun `resolution with Failed status is not usable`() {
        val resolution = WorkspaceResolution(
            dependencies = emptyList(),
            sourceDirectories = emptyList(),
            status = ResolutionStatus.Failed("INIT_SCRIPT_ERROR", "Init script failed"),
        )

        assertTrue(resolution.status is ResolutionStatus.Failed)
        assertFalse(resolution.isUsable)
    }
}
