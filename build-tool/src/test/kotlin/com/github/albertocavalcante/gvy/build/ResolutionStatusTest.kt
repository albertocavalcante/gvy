package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolutionStatusTest {

    @Test
    fun `Success status is usable`() {
        val status = ResolutionStatus.Success
        assertTrue(status.isUsable)
    }

    @Test
    fun `Warning status is usable`() {
        val status = ResolutionStatus.Warning(
            code = "ZERO_DEPENDENCIES",
            message = "No dependencies found",
        )
        assertTrue(status.isUsable)
    }

    @Test
    fun `Failed status is not usable`() {
        val status = ResolutionStatus.Failed(
            code = "TOOLCHAIN_PROVISIONING_FAILED",
            message = "Could not provision toolchain",
        )
        assertFalse(status.isUsable)
    }

    @Test
    fun `Failed status with cause is not usable`() {
        val cause = RuntimeException("Root cause")
        val status = ResolutionStatus.Failed(
            code = "DEPENDENCY_RESOLUTION_FAILED",
            message = "Resolution failed",
            cause = cause,
        )
        assertFalse(status.isUsable)
        assertEquals(cause, status.cause)
    }
}
