// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

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

    @ParameterizedTest(name = "Groovy {0} on JDK {1}: full={2}, partial={3}")
    @CsvSource(
        "6.0.0,        16, false, false, 'Groovy 6.0 requires JDK 17 or later, but JDK 16 is being used'",
        "6.0.0,        17, true,  false, 'Groovy 6.0 is fully compatible with JDK 17'",
        "6.0.0,        27, true,  false, 'Groovy 6.0 is fully compatible with JDK 27'",
        "6.0.0,        28, false, false, 'Groovy 6.0 is not compatible with JDK 28 (max supported: JDK 27)'",
        "6.0.0-alpha-1, 11, false, false, 'Groovy 6.0 requires JDK 17 or later, but JDK 11 is being used'",
        "6.0.0-beta-2,  11, false, false, 'Groovy 6.0 requires JDK 17 or later, but JDK 11 is being used'",
        "6.0.0-RC-3,    11, false, false, 'Groovy 6.0 requires JDK 17 or later, but JDK 11 is being used'",
        "5.1.3,        10, false, false, 'Groovy 5.1 requires JDK 11 or later, but JDK 10 is being used'",
        "5.1.3,        11, true,  false, 'Groovy 5.1 is fully compatible with JDK 11'",
        "5.1.3,        25, true,  false, 'Groovy 5.1 is fully compatible with JDK 25'",
        "5.1.3,        26, false, true,  " +
            "'Groovy 5.1 has partial/experimental support for JDK 26 (fully supports up to JDK 25)'",
        "5.1.3,        27, false, true,  " +
            "'Groovy 5.1 has partial/experimental support for JDK 27 (fully supports up to JDK 25)'",
        "5.1.3,        28, false, false, 'Groovy 5.1 is not compatible with JDK 28 (max supported: JDK 27)'",
    )
    fun `should check Groovy 5 1 and 6 JDK boundaries`(
        groovyVersion: String,
        jdkVersion: Int,
        expectedFull: Boolean,
        expectedPartial: Boolean,
        expectedMessage: String,
    ) {
        val result = service.checkCompatibility(groovyVersion, jdkVersion)

        assertEquals(CompatibilityResult(expectedFull, expectedPartial, expectedMessage), result)
    }

    @ParameterizedTest(name = "JDK {0} -> {1}")
    @CsvSource(
        "8,  Groovy 4.0",
        "11, Groovy 5.1",
        "16, Groovy 5.1",
        "17, Groovy 6.0",
        "21, Groovy 6.0",
        "25, Groovy 6.0",
        "26, Groovy 6.0",
        "27, Groovy 6.0",
    )
    fun `should suggest the newest Groovy line that fully supports the JDK`(jdkVersion: Int, expected: String) {
        assertEquals(expected, service.suggestGroovyVersion(jdkVersion))
    }

    @Test
    fun `should suggest nothing for a JDK newer than every Groovy line`() {
        assertNull(service.suggestGroovyVersion(28))
    }

    @Test
    fun `should suggest the newest line regardless of the order of the entries`() {
        val entries = listOf(
            CompatibilityEntry(groovy = "4.0", jdkMin = 8, jdkMax = 21),
            CompatibilityEntry(groovy = "6.0", jdkMin = 17, jdkMax = 27),
            CompatibilityEntry(groovy = "2.4", jdkMin = 7, jdkMax = 8),
            CompatibilityEntry(groovy = "5.1", jdkMin = 11, jdkMax = 25),
            CompatibilityEntry(groovy = "5.0", jdkMin = 11, jdkMax = 25),
        )

        for (ordering in listOf(entries, entries.reversed(), entries.sortedBy { it.groovy })) {
            val shuffled = GroovyCompatibilityService { ordering }

            assertEquals("Groovy 6.0", shuffled.suggestGroovyVersion(21), "Entries: ${ordering.map { it.groovy }}")
            assertEquals("Groovy 5.1", shuffled.suggestGroovyVersion(11), "Entries: ${ordering.map { it.groovy }}")
            assertEquals("Groovy 4.0", shuffled.suggestGroovyVersion(8), "Entries: ${ordering.map { it.groovy }}")
        }
    }

    @Test
    fun `bundled compatibility table should be well formed`() {
        val entries = loadBundledCompatibilityEntries()

        assertTrue(entries.isNotEmpty(), "The bundled table should load")
        assertEquals(entries.size, entries.map { it.groovy }.toSet().size, "Groovy lines should be unique")
        for (entry in entries) {
            assertTrue(entry.jdkMax >= entry.jdkMin, "Groovy ${entry.groovy}: jdkMax must not be below jdkMin")
            val partial = entry.jdkMaxPartial
            assertTrue(
                partial == null || partial > entry.jdkMax,
                "Groovy ${entry.groovy}: jdkMaxPartial must exceed jdkMax",
            )
        }
    }

    // The next two tests characterise existing behaviour: a Groovy line that is not in the table is reported as
    // unknown (and fails open) rather than being matched to a neighbouring entry. Fail-open itself is tracked apart.

    @Test
    fun `characterisation - Groovy 7 is not in the table and is reported as unknown`() {
        // Groovy 6.0 would reject JDK 8, so a message naming 7.0 as unknown shows no neighbouring entry was used.
        val result = service.checkCompatibility("7.0.0", 8)

        assertTrue(result.isFullyCompatible, "Unknown versions fail open")
        assertTrue(
            result.message.startsWith("Unknown Groovy version 7.0"),
            "Message should report the version as unknown: ${result.message}",
        )
    }

    @Test
    fun `characterisation - Groovy 5 2 is not in the table and is reported as unknown`() {
        val result = service.checkCompatibility("5.2.0", 8)

        assertTrue(result.isFullyCompatible, "Unknown versions fail open")
        assertTrue(
            result.message.startsWith("Unknown Groovy version 5.2"),
            "Message should report the version as unknown: ${result.message}",
        )
    }
}
