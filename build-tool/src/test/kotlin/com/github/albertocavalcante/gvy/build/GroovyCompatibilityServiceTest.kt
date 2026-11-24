// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import com.github.albertocavalcante.gvy.build.CompatibilityResult.Compatible
import com.github.albertocavalcante.gvy.build.CompatibilityResult.PartiallySupported
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class GroovyCompatibilityServiceTest {

    private val service = GroovyCompatibilityService()

    @Test
    fun `should load compatibility matrix from JSON resource`() {
        // A known line is answered from the table, not reported as unknown, so the resource loaded.
        assertEquals(Compatible("5.0", 25), service.checkCompatibility("5.0.0", 25))
    }

    @ParameterizedTest(name = "Groovy {0} on JDK {1} is fully compatible")
    @CsvSource(
        "5.0.0, 25, 5.0",
        "5.0.0, 11, 5.0",
        "4.0.0, 21, 4.0",
        "3.0.0, 17, 3.0",
        "2.5.0, 8,  2.5",
        "2.5.0, 11, 2.5",
        "2.4.0, 7,  2.4",
        "5.0.1, 25, 5.0",
        "4.0.22, 21, 4.0",
        "5.0.0-rc-1, 25, 5.0",
    )
    fun `should report fully compatible combinations`(groovyVersion: String, jdkVersion: Int, line: String) {
        assertEquals(Compatible(line, jdkVersion), service.checkCompatibility(groovyVersion, jdkVersion))
    }

    @Test
    fun `should report Groovy 4 incompatible with JDK 25`() {
        val result = service.checkCompatibility("4.0.0", 25)

        assertEquals(CompatibilityResult.Incompatible.AboveMaximum("4.0", 25, maxSupportedJdk = 23), result)
        assertEquals("Groovy 4.0 is not compatible with JDK 25 (max supported: JDK 23)", result.message)
    }

    @Test
    fun `should report Groovy 3 incompatible with JDK 21 and 25`() {
        assertEquals(
            CompatibilityResult.Incompatible.AboveMaximum("3.0", 21, maxSupportedJdk = 17),
            service.checkCompatibility("3.0.0", 21),
        )
        assertEquals(
            CompatibilityResult.Incompatible.AboveMaximum("3.0", 25, maxSupportedJdk = 17),
            service.checkCompatibility("3.0.0", 25),
        )
    }

    @ParameterizedTest(name = "Groovy 4.0.0 on JDK {0} is partially supported")
    @CsvSource("22", "23")
    fun `should report Groovy 4 partially supported with JDK 22 and 23`(jdkVersion: Int) {
        val result = service.checkCompatibility("4.0.0", jdkVersion)

        assertEquals(PartiallySupported("4.0", jdkVersion, fullySupportedUpToJdk = 21), result)
        assertEquals(
            "Groovy 4.0 has partial/experimental support for JDK $jdkVersion (fully supports up to JDK 21)",
            result.message,
        )
    }

    @ParameterizedTest(name = "Groovy {0} on JDK 21 is unknown")
    @CsvSource("99.0.0", "not-a-version", "7.0.0", "5.2.0", "7", "''")
    fun `should report versions outside the table as unknown with the version asked about`(groovyVersion: String) {
        val result = service.checkCompatibility(groovyVersion, 21)

        assertEquals(CompatibilityResult.Unknown(groovyVersion), result)
        assertEquals("Unknown Groovy version $groovyVersion - no compatibility data", result.message)
    }

    @Test
    fun `should report Groovy 7 on JDK 8 as unknown and not as the neighbouring line`() {
        // Groovy 6.0 would reject JDK 8, so Unknown shows no neighbouring entry was used.
        assertEquals(CompatibilityResult.Unknown("7.0.0"), service.checkCompatibility("7.0.0", 8))
    }

    @Test
    fun `should report Groovy 5 2 on JDK 8 as unknown and not as the neighbouring line`() {
        assertEquals(CompatibilityResult.Unknown("5.2.0"), service.checkCompatibility("5.2.0", 8))
    }

    @Test
    fun `should report every version as unknown when the table failed to load`() {
        val empty = GroovyCompatibilityService { emptyList() }

        assertEquals(CompatibilityResult.Unknown("5.0.0"), empty.checkCompatibility("5.0.0", 21))
        assertNull(empty.suggestGroovyVersion(21))
    }

    @Test
    fun `should only report Compatible for a known line within its JDK range`() {
        for ((version, jdk) in listOf("7.0.0" to 8, "5.2.0" to 8, "99.0.0" to 21, "x" to 21)) {
            val result = service.checkCompatibility(version, jdk)
            assertInstanceOf(CompatibilityResult.Unknown::class.java, result, "$version on JDK $jdk")
        }
    }

    @Test
    fun `should suggest nothing for a JDK older than every Groovy line`() {
        assertNull(service.suggestGroovyVersion(6))
    }

    // JDK bounds come from the release notes JDK requirements sections:
    // - Groovy 6.0: https://groovy-lang.org/releasenotes/groovy-6.0.html ("tested on JDK versions 17 through 27")
    // - Groovy 5.1: https://groovy-lang.org/releasenotes/groovy-5.1.html ("tested on JDK versions 11 through 25,
    //   and additionally on early access builds of JDK 26 and 27")

    @ParameterizedTest(name = "Groovy {0} on JDK {1}")
    @MethodSource("groovy6Boundaries", "groovy51Boundaries")
    fun `should check Groovy 5 1 and 6 JDK boundaries`(
        groovyVersion: String,
        jdkVersion: Int,
        expected: CompatibilityResult,
        expectedMessage: String,
    ) {
        val result = service.checkCompatibility(groovyVersion, jdkVersion)

        assertEquals(expected, result)
        assertEquals(expectedMessage, result.message)
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

    private companion object {
        fun belowMinimum(line: String, jdk: Int, min: Int) =
            CompatibilityResult.Incompatible.BelowMinimum(line, jdk, min)

        @JvmStatic
        fun groovy6Boundaries(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "6.0.0",
                16,
                belowMinimum("6.0", 16, 17),
                "Groovy 6.0 requires JDK 17 or later, but JDK 16 is being used",
            ),
            Arguments.of("6.0.0", 17, Compatible("6.0", 17), "Groovy 6.0 is fully compatible with JDK 17"),
            Arguments.of("6.0.0", 27, Compatible("6.0", 27), "Groovy 6.0 is fully compatible with JDK 27"),
            Arguments.of(
                "6.0.0",
                28,
                CompatibilityResult.Incompatible.AboveMaximum("6.0", 28, 27),
                "Groovy 6.0 is not compatible with JDK 28 (max supported: JDK 27)",
            ),
            Arguments.of(
                "6.0.0-alpha-1",
                11,
                belowMinimum("6.0", 11, 17),
                "Groovy 6.0 requires JDK 17 or later, but JDK 11 is being used",
            ),
            Arguments.of(
                "6.0.0-beta-2",
                11,
                belowMinimum("6.0", 11, 17),
                "Groovy 6.0 requires JDK 17 or later, but JDK 11 is being used",
            ),
            Arguments.of(
                "6.0.0-RC-3",
                11,
                belowMinimum("6.0", 11, 17),
                "Groovy 6.0 requires JDK 17 or later, but JDK 11 is being used",
            ),
        )

        @JvmStatic
        fun groovy51Boundaries(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "5.1.3",
                10,
                belowMinimum("5.1", 10, 11),
                "Groovy 5.1 requires JDK 11 or later, but JDK 10 is being used",
            ),
            Arguments.of("5.1.3", 11, Compatible("5.1", 11), "Groovy 5.1 is fully compatible with JDK 11"),
            Arguments.of("5.1.3", 25, Compatible("5.1", 25), "Groovy 5.1 is fully compatible with JDK 25"),
            Arguments.of(
                "5.1.3",
                26,
                PartiallySupported("5.1", 26, 25),
                "Groovy 5.1 has partial/experimental support for JDK 26 (fully supports up to JDK 25)",
            ),
            Arguments.of(
                "5.1.3",
                27,
                PartiallySupported("5.1", 27, 25),
                "Groovy 5.1 has partial/experimental support for JDK 27 (fully supports up to JDK 25)",
            ),
            Arguments.of(
                "5.1.3",
                28,
                CompatibilityResult.Incompatible.AboveMaximum("5.1", 28, 27),
                "Groovy 5.1 is not compatible with JDK 28 (max supported: JDK 27)",
            ),
        )
    }
}
