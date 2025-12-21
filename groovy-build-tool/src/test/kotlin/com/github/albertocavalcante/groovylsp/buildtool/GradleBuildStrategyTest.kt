package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class GradleBuildStrategyTest {

    @ParameterizedTest
    @CsvSource(
        "auto, AUTO",
        "AUTO, AUTO",
        "Auto, AUTO",
        "bsp, BSP_PREFERRED",
        "BSP, BSP_PREFERRED",
        "bsp_preferred, BSP_PREFERRED",
        "bsp-preferred, BSP_PREFERRED",
        "BSP_PREFERRED, BSP_PREFERRED",
        "native, NATIVE_ONLY",
        "NATIVE, NATIVE_ONLY",
        "native_only, NATIVE_ONLY",
        "native-only, NATIVE_ONLY",
        "NATIVE_ONLY, NATIVE_ONLY",
    )
    fun `fromString parses valid values correctly`(input: String, expected: String) {
        val expectedStrategy = GradleBuildStrategy.valueOf(expected)
        assertEquals(expectedStrategy, GradleBuildStrategy.fromString(input))
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["", "  ", "unknown", "gradle", "tooling-api", "invalid"])
    fun `fromString returns AUTO for null or unrecognized values`(input: String?) {
        assertEquals(GradleBuildStrategy.AUTO, GradleBuildStrategy.fromString(input))
    }

    @Test
    fun `fromString handles whitespace`() {
        assertEquals(GradleBuildStrategy.BSP_PREFERRED, GradleBuildStrategy.fromString("  bsp  "))
        assertEquals(GradleBuildStrategy.NATIVE_ONLY, GradleBuildStrategy.fromString("\tnative\n"))
    }
}
