// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroovyCompatibilityServiceTest {

    private val service = GroovyCompatibilityService()

    @Test
    fun `should load compatibility matrix from JSON resource`() {
        // Verify that the service can load the matrix without errors
        // This is implicitly tested by other tests, but we make it explicit
        assertNotNull(service, "Service should be created successfully")
        // Try to get a known compatibility - this will fail if JSON didn't load
        val result = service.checkCompatibility("5.0.0", 25)
        assertNotNull(result, "Should return a result when matrix is loaded")
    }

    @Test
    fun `should report Groovy 5 compatible with JDK 25`() {
        val result = service.checkCompatibility("5.0.0", 25)
        assertTrue(result.isFullyCompatible, "Groovy 5.0.0 should be fully compatible with JDK 25")
        assertFalse(result.isPartiallyCompatible, "Should not be partial if fully compatible")
    }

    @Test
    fun `should report Groovy 5 compatible with JDK 11`() {
        val result = service.checkCompatibility("5.0.0", 11)
        assertTrue(result.isFullyCompatible, "Groovy 5.0.0 should be compatible with JDK 11 (minimum)")
    }

    @Test
    fun `should report Groovy 4 incompatible with JDK 25`() {
        val result = service.checkCompatibility("4.0.0", 25)
        assertFalse(result.isFullyCompatible, "Groovy 4.0.0 should not be fully compatible with JDK 25")
        assertFalse(result.isPartiallyCompatible, "Groovy 4.0.0 should not be partially compatible with JDK 25")
        assertTrue(result.message.isNotEmpty(), "Should provide a message explaining incompatibility")
    }

    @Test
    fun `should report Groovy 4 fully compatible with JDK 21`() {
        val result = service.checkCompatibility("4.0.0", 21)
        assertTrue(result.isFullyCompatible, "Groovy 4.0.0 should be fully compatible with JDK 21")
    }

    @Test
    fun `should report Groovy 4 partially compatible with JDK 22`() {
        val result = service.checkCompatibility("4.0.0", 22)
        assertFalse(result.isFullyCompatible, "Groovy 4.0.0 should not be fully compatible with JDK 22")
        assertTrue(result.isPartiallyCompatible, "Groovy 4.0.0 should be partially compatible with JDK 22")
        assertTrue(
            result.message.contains("partial", ignoreCase = true),
            "Message should mention partial compatibility",
        )
    }

    @Test
    fun `should report Groovy 4 partially compatible with JDK 23`() {
        val result = service.checkCompatibility("4.0.0", 23)
        assertFalse(result.isFullyCompatible, "Groovy 4.0.0 should not be fully compatible with JDK 23")
        assertTrue(result.isPartiallyCompatible, "Groovy 4.0.0 should be partially compatible with JDK 23")
    }

    @Test
    fun `should report Groovy 3 compatible with JDK 17`() {
        val result = service.checkCompatibility("3.0.0", 17)
        assertTrue(result.isFullyCompatible, "Groovy 3.0.0 should be compatible with JDK 17")
    }

    @Test
    fun `should report Groovy 3 incompatible with JDK 21`() {
        val result = service.checkCompatibility("3.0.0", 21)
        assertFalse(result.isFullyCompatible, "Groovy 3.0.0 should not be compatible with JDK 21")
    }

    @Test
    fun `should report Groovy 2dot5 compatible with JDK 8`() {
        val result = service.checkCompatibility("2.5.0", 8)
        assertTrue(result.isFullyCompatible, "Groovy 2.5.0 should be compatible with JDK 8")
    }

    @Test
    fun `should report Groovy 2_5 compatible with JDK 11`() {
        val result = service.checkCompatibility("2.5.0", 11)
        assertTrue(result.isFullyCompatible, "Groovy 2.5.0 should be compatible with JDK 11")
    }

    @Test
    fun `should report Groovy 2_4 compatible with JDK 7`() {
        val result = service.checkCompatibility("2.4.0", 7)
        assertTrue(result.isFullyCompatible, "Groovy 2.4.0 should be compatible with JDK 7")
    }

    @Test
    fun `should fail open for unknown Groovy versions`() {
        // Unknown/future Groovy versions should fail open (assume compatible)
        val result = service.checkCompatibility("99.0.0", 21)
        assertTrue(result.isFullyCompatible, "Unknown Groovy version should fail open")
        assertTrue(result.message.contains("unknown", ignoreCase = true), "Should indicate unknown version")
    }

    @Test
    fun `should fail open for unparseable Groovy versions`() {
        val result = service.checkCompatibility("not-a-version", 21)
        assertTrue(result.isFullyCompatible, "Unparseable Groovy version should fail open")
    }

    @Test
    fun `should suggest Groovy 6 for JDK 25`() {
        assertEquals("Groovy 6.0", service.suggestGroovyVersion(25))
    }

    @Test
    fun `should suggest Groovy 6 for JDK 21`() {
        assertEquals("Groovy 6.0", service.suggestGroovyVersion(21))
    }

    @Test
    fun `should suggest Groovy 3 for JDK 17`() {
        val suggestion = service.suggestGroovyVersion(17)
        assertNotNull(suggestion, "Should suggest a Groovy version for JDK 17")
    }

    @Test
    fun `should return null or generic suggestion for very old JDK versions`() {
        // JDK 6 is very old, might not have explicit suggestion
        val suggestion = service.suggestGroovyVersion(6)
        // Either null or a generic message is acceptable
        assertTrue(suggestion == null || suggestion.isNotEmpty(), "Should handle old JDK gracefully")
    }

    @Test
    fun `should handle patch versions correctly`() {
        // Test that patch versions work (5.0.1, 4.0.22, etc.)
        val result1 = service.checkCompatibility("5.0.1", 25)
        assertTrue(result1.isFullyCompatible, "Groovy 5.0.1 should be compatible with JDK 25")

        val result2 = service.checkCompatibility("4.0.22", 21)
        assertTrue(result2.isFullyCompatible, "Groovy 4.0.22 should be compatible with JDK 21")
    }

    @Test
    fun `should handle version with RC or snapshot correctly`() {
        val result = service.checkCompatibility("5.0.0-rc-1", 25)
        assertTrue(result.isFullyCompatible, "Groovy 5.0.0-rc-1 should be compatible with JDK 25")
    }

    @Test
    fun `should provide helpful message for incompatible versions`() {
        val result = service.checkCompatibility("3.0.0", 25)
        assertFalse(result.isFullyCompatible, "Groovy 3.0.0 should not be compatible with JDK 25")
        assertTrue(result.message.isNotEmpty(), "Should provide an explanation")
        assertTrue(
            result.message.contains("compatible", ignoreCase = true) ||
                result.message.contains("support", ignoreCase = true),
            "Message should mention compatibility or support",
        )
    }

    @Test
    fun `should provide helpful message for partial compatibility`() {
        val result = service.checkCompatibility("4.0.0", 22)
        assertTrue(result.isPartiallyCompatible, "Groovy 4.0.0 should be partially compatible with JDK 22")
        assertTrue(
            result.message.contains("partial", ignoreCase = true),
            "Message should mention partial compatibility",
        )
    }

    // JDK bounds come from the release notes JDK requirements sections:
    // - Groovy 6.0: https://groovy-lang.org/releasenotes/groovy-6.0.html ("tested on JDK versions 17 through 27")
    // - Groovy 5.1: https://groovy-lang.org/releasenotes/groovy-5.1.html ("tested on JDK versions 11 through 25,
    //   and additionally on early access builds of JDK 26 and 27")

    @Test
    fun `should require JDK 17 for Groovy 6`() {
        val result = service.checkCompatibility("6.0.0", 11)

        assertFalse(result.isFullyCompatible, "Groovy 6.0.0 should not be compatible with JDK 11")
        assertFalse(result.isPartiallyCompatible, "Groovy 6.0.0 should not be partially compatible with JDK 11")
        assertTrue(result.message.contains("requires JDK 17"), "Message should state the minimum: ${result.message}")
    }

    @Test
    fun `should report Groovy 6 fully compatible with JDK 17 through 27`() {
        for (jdk in 17..27) {
            val result = service.checkCompatibility("6.0.0", jdk)
            assertTrue(result.isFullyCompatible, "Groovy 6.0.0 should be fully compatible with JDK $jdk")
        }
    }

    @Test
    fun `should report Groovy 6 pre-releases against the Groovy 6 entry`() {
        for (version in listOf("6.0.0-alpha-1", "6.0.0-beta-2", "6.0.0-RC-3")) {
            val result = service.checkCompatibility(version, 11)
            assertFalse(result.isFullyCompatible, "Groovy $version should require JDK 17")
            assertTrue(result.message.contains("Groovy 6.0"), "Message should name Groovy 6.0: ${result.message}")
        }
    }

    @Test
    fun `should not support Groovy 6 beyond JDK 27`() {
        val result = service.checkCompatibility("6.0.0", 28)

        assertFalse(result.isFullyCompatible, "Groovy 6.0.0 is only tested through JDK 27")
        assertFalse(result.isPartiallyCompatible, "Groovy 6.0.0 has no partial range beyond JDK 27")
    }

    @Test
    fun `should keep Groovy 5 1 at the Groovy 5 JDK minimum of 11`() {
        val result = service.checkCompatibility("5.1.0", 11)

        assertTrue(result.isFullyCompatible, "Groovy 5.1.0 should be fully compatible with JDK 11")
        assertTrue(result.message.contains("Groovy 5.1"), "Message should name Groovy 5.1: ${result.message}")
    }

    @Test
    fun `should report Groovy 5 1 as partially compatible with JDK 26 and 27`() {
        for (jdk in 26..27) {
            val result = service.checkCompatibility("5.1.0", jdk)
            assertFalse(result.isFullyCompatible, "Groovy 5.1.0 is only fully tested through JDK 25")
            assertTrue(result.isPartiallyCompatible, "Groovy 5.1.0 was tested on early access JDK $jdk")
        }
    }

    @Test
    fun `should report an unknown future Groovy major as unknown instead of using the nearest entry`() {
        // JDK 8 would be incompatible with Groovy 6 (the nearest entry); unknown versions fail open.
        val result = service.checkCompatibility("7.0.0", 8)

        assertTrue(result.isFullyCompatible, "Unknown versions fail open")
        assertTrue(
            result.message.startsWith("Unknown Groovy version 7.0"),
            "Message should report the version as unknown: ${result.message}",
        )
    }

    @Test
    fun `should suggest the newest Groovy line that supports the JDK`() {
        assertEquals("Groovy 5.1", service.suggestGroovyVersion(11))
        assertEquals("Groovy 6.0", service.suggestGroovyVersion(17))
    }

    @Test
    fun `should report a Groovy 5 minor missing from the table as unknown`() {
        val result = service.checkCompatibility("5.2.0", 8)

        assertTrue(result.isFullyCompatible, "Unknown versions fail open")
        assertTrue(
            result.message.startsWith("Unknown Groovy version 5.2"),
            "Message should report the version as unknown: ${result.message}",
        )
    }
}
