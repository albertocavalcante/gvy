package com.github.albertocavalcante.gvy.build

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

        assertFalse(resolution.isUsable)
        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())

        val status = resolution.status
        assertTrue(status is ResolutionStatus.Failed)
        assertEquals("TOOLCHAIN_PROVISIONING_FAILED", status.code)
        assertEquals("Failed to provision toolchain", status.message)
    }

    @Test
    fun `failed factory with cause creates Failed status with cause`() {
        val cause = RuntimeException("Root cause")
        val resolution = WorkspaceResolution.failed(
            code = "DEPENDENCY_RESOLUTION_FAILED",
            message = "Resolution failed",
            cause = cause,
        )

        assertFalse(resolution.isUsable)

        val status = resolution.status
        assertTrue(status is ResolutionStatus.Failed)
        assertEquals(cause, status.cause)
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
