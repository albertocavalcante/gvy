package com.github.albertocavalcante.groovylsp.buildtool.gradle

import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

        // Java 17 requires Gradle 7.3+
        assertTrue(service.isCompatible("7.3", 17))
        assertTrue(service.isCompatible("8.0", 17))
        assertFalse(service.isCompatible("7.2", 17))
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
        assertEquals("7.3", service.getMinimumGradleVersion(17))
        // Unknown or unset
        assertEquals(null, service.getMinimumGradleVersion(8))
    }
}
