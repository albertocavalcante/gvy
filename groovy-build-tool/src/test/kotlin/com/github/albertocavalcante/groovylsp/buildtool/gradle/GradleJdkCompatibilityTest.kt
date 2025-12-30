package com.github.albertocavalcante.groovylsp.buildtool.gradle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradleJdkCompatibilityTest {

    @Test
    fun `isSupported returns false for Gradle 8_0_2 with JDK 21`() {
        assertFalse(GradleJdkCompatibility.isSupported("8.0.2", 21))
    }

    @Test
    fun `isSupported returns true for Gradle 8_5 with JDK 21`() {
        assertTrue(GradleJdkCompatibility.isSupported("8.5", 21))
    }

    @Test
    fun `isSupported returns true for Gradle 8_10 with JDK 21`() {
        assertTrue(GradleJdkCompatibility.isSupported("8.10", 21))
    }

    @Test
    fun `isSupported returns false for pre-release Gradle version with JDK 21`() {
        // Gradle 8.5-rc-1 is older than 8.5 GA, so strictly it might not be supported if 8.5 is required
        assertFalse(GradleJdkCompatibility.isSupported("8.5-rc-1", 21))
    }

    @Test
    fun `isSupported returns false for Gradle 7_5 with JDK 20`() {
        assertFalse(GradleJdkCompatibility.isSupported("7.5", 20))
    }

    @Test
    fun `isSupported returns true for Gradle 8_3 with JDK 20`() {
        assertTrue(GradleJdkCompatibility.isSupported("8.3", 20))
    }

    @Test
    fun `isSupported returns true for any Gradle with JDK 16`() {
        // JDK 16 and below have no strict minimum requirement in our matrix
        assertTrue(GradleJdkCompatibility.isSupported("5.0", 16))
        assertTrue(GradleJdkCompatibility.isSupported("7.0", 16))
    }

    @Test
    fun `isSupported handles invalid Gradle version gracefully`() {
        assertFalse(GradleJdkCompatibility.isSupported("not-a-version", 21))
    }

    @Test
    fun `getMinimumGradleVersion returns 8_5 for JDK 21`() {
        assertEquals("8.5", GradleJdkCompatibility.getMinimumGradleVersion(21))
    }

    @Test
    fun `getMinimumGradleVersion returns 7_2 for JDK 17`() {
        assertEquals("7.2", GradleJdkCompatibility.getMinimumGradleVersion(17))
    }

    @Test
    fun `getMinimumGradleVersion returns null for JDK 16`() {
        assertNull(GradleJdkCompatibility.getMinimumGradleVersion(16))
    }

    @Test
    fun `suggestFix provides actionable message for incompatible versions`() {
        val suggestion = GradleJdkCompatibility.suggestFix("8.0.2", 21)

        assertTrue(suggestion.contains("8.0.2"))
        assertTrue(suggestion.contains("21"))
        assertTrue(suggestion.contains("8.5"))
        assertTrue(suggestion.contains("Upgrade"))
        assertTrue(suggestion.contains("wrapper"))
        // Validates the dynamic max JDK suggestion (Gradle 8.0.2 supports up to JDK 19)
        assertTrue(suggestion.contains("19 for Gradle 8.0.2"))
    }

    @Test
    fun `suggestFix handles unknown JDK version gracefully`() {
        val suggestion = GradleJdkCompatibility.suggestFix("7.0", 99)

        assertNotNull(suggestion)
        assertTrue(suggestion.contains("may not be compatible"))
    }

    @Test
    fun `getCurrentJdkMajorVersion returns current runtime version`() {
        val current = GradleJdkCompatibility.getCurrentJdkMajorVersion()
        assertTrue(current >= 17, "Expected JDK 17 or higher, got $current")
    }
}
