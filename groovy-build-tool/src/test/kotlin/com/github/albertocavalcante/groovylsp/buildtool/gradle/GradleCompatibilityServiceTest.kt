package com.github.albertocavalcante.groovylsp.buildtool.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleCompatibilityServiceTest {

    private val service = GradleCompatibilityService()

    @Test
    fun `should determine compatibility correctly`() {
        // Based on assumed JSON content (to be created)
        // Java 21 requires Gradle 8.5+
        assertTrue(service.isCompatible("8.5", 21))
        assertTrue(service.isCompatible("8.10", 21))
        assertFalse(service.isCompatible("8.4", 21))
        assertFalse(service.isCompatible("8.0", 21))

        // Java 17 requires Gradle 7.2+
        assertTrue(service.isCompatible("7.3", 17))
        assertTrue(service.isCompatible("8.0", 17))
        assertTrue(service.isCompatible("7.2", 17))
        assertFalse(service.isCompatible("7.1", 17))
    }

    @Test
    fun `should suggest correct fixes`() {
        val suggestion = service.suggestFix("8.0", 21)

        // Should mention the required version (8.5+)
        assertNotNull(suggestion, "Suggestion should not be null for incompatible version")
        assertTrue(suggestion!!.contains("Gradle 8.5"), "Suggestion should mention Gradle 8.5")
        assertTrue(suggestion.contains("JDK 21"), "Suggestion should mention JDK 21")

        // Should be null if compatible
        assertEquals(null, service.suggestFix("8.5", 21))
    }

    @Test
    fun `should return minimum gradle version`() {
        assertEquals("8.5", service.getMinimumGradleVersion(21))
        assertEquals("7.2", service.getMinimumGradleVersion(17))
        // Unknown or unset
        assertEquals(null, service.getMinimumGradleVersion(8))
    }

    // Edge case tests

    @Test
    fun `should treat release candidate as less than release`() {
        // RC versions are pre-release, so 8.5-rc-1 < 8.5
        // For JDK 21 requiring 8.5+, an RC is NOT compatible
        assertFalse(service.isCompatible("8.5-rc-1", 21))
        // But 8.6-rc-1 IS compatible (>= 8.5)
        assertTrue(service.isCompatible("8.6-rc-1", 21))
    }

    @Test
    fun `should treat milestone as less than release`() {
        // Milestone versions are pre-release, so 8.5-milestone-1 < 8.5
        assertFalse(service.isCompatible("8.5-milestone-1", 21))
    }

    @Test
    fun `should fail open for unknown JDK versions`() {
        // Future JDK versions not in compatibility matrix should assume compatible
        assertTrue(service.isCompatible("8.5", 25), "Unknown JDK 25 should assume compatible")
        assertTrue(service.isCompatible("9.0", 30), "Unknown JDK 30 should assume compatible")
    }

    @Test
    fun `should fail open for malformed Gradle version`() {
        // Unparseable versions should fail open (return true)
        assertTrue(service.isCompatible("not-a-version", 21))
        assertTrue(service.isCompatible("", 21))
    }

    @Test
    fun `suggestFix returns null for unknown JDK`() {
        // No suggestion for unknown JDK (not in matrix)
        assertNull(service.suggestFix("7.0", 8))
    }
}
