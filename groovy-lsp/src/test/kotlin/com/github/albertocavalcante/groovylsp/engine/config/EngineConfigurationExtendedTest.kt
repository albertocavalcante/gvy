package com.github.albertocavalcante.groovylsp.engine.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Extended tests for [EngineType], [EngineConfiguration], and [EngineFeatures].
 *
 * Covers edge cases, data class behavior, and exhaustive type coverage.
 */
class EngineConfigurationExtendedTest {

    // =========================================================================
    // EngineType Tests
    // =========================================================================

    @Test
    fun `EngineType fromString handles whitespace in input`() {
        // Leading/trailing whitespace should not match (case-sensitive to pattern)
        assertEquals(EngineType.Core, EngineType.fromString(" native"))
        assertEquals(EngineType.Core, EngineType.fromString("native "))
        assertEquals(EngineType.Core, EngineType.fromString(" core "))
    }

    @Test
    fun `EngineType fromString handles mixed case variations`() {
        assertEquals(EngineType.Native, EngineType.fromString("nAtIvE"))
        assertEquals(EngineType.Core, EngineType.fromString("cOrE"))
        assertEquals(EngineType.OpenRewrite, EngineType.fromString("oPeNrEwRiTe"))
    }

    @Test
    fun `EngineType entries order is Native, Core, OpenRewrite`() {
        val entries = EngineType.entries
        assertEquals(EngineType.Native, entries[0])
        assertEquals(EngineType.Core, entries[1])
        assertEquals(EngineType.OpenRewrite, entries[2])
    }

    @Test
    fun `EngineType id values are lowercase`() {
        for (type in EngineType.entries) {
            assertEquals(type.id, type.id.lowercase())
        }
    }

    @Test
    fun `EngineType toString includes type name`() {
        assertTrue(EngineType.Native.toString().contains("Native"))
        assertTrue(EngineType.Core.toString().contains("Core"))
        assertTrue(EngineType.OpenRewrite.toString().contains("OpenRewrite"))
    }

    @Test
    fun `EngineType equality works correctly`() {
        assertEquals(EngineType.Native, EngineType.Native)
        assertEquals(EngineType.Core, EngineType.Core)
        assertNotEquals<EngineType>(EngineType.Native, EngineType.Core)
    }

    @Test
    fun `EngineType hashCode is consistent`() {
        assertEquals(EngineType.Native.hashCode(), EngineType.Native.hashCode())
        assertEquals(EngineType.Core.hashCode(), EngineType.Core.hashCode())
    }

    // =========================================================================
    // EngineConfiguration Tests
    // =========================================================================

    @Test
    fun `EngineConfiguration equality based on properties`() {
        val config1 = EngineConfiguration(type = EngineType.Core)
        val config2 = EngineConfiguration(type = EngineType.Core)
        val config3 = EngineConfiguration(type = EngineType.Native)

        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
    }

    @Test
    fun `EngineConfiguration copy works correctly`() {
        val original = EngineConfiguration(type = EngineType.Native)
        val copied = original.copy(type = EngineType.Core)

        assertEquals(EngineType.Native, original.type)
        assertEquals(EngineType.Core, copied.type)
    }

    @Test
    fun `EngineConfiguration with all Native features`() {
        val features = EngineFeatures(typeInference = true, flowAnalysis = true)
        val config = EngineConfiguration(type = EngineType.Native, features = features)

        assertEquals(EngineType.Native, config.type)
        assertTrue(config.features.typeInference)
        assertTrue(config.features.flowAnalysis)
    }

    @Test
    fun `EngineConfiguration with all Core features`() {
        val features = EngineFeatures(typeInference = true, flowAnalysis = true)
        val config = EngineConfiguration(type = EngineType.Core, features = features)

        assertEquals(EngineType.Core, config.type)
        assertTrue(config.features.typeInference)
        assertTrue(config.features.flowAnalysis)
    }

    @Test
    fun `EngineConfiguration hashCode is consistent`() {
        val config1 = EngineConfiguration(type = EngineType.Core)
        val config2 = EngineConfiguration(type = EngineType.Core)

        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `EngineConfiguration toString includes type`() {
        val config = EngineConfiguration(type = EngineType.Core)
        val str = config.toString()

        assertTrue(str.contains("EngineConfiguration"))
        assertTrue(str.contains("Core"))
    }

    @Test
    fun `EngineConfiguration destructuring works`() {
        val config = EngineConfiguration(
            type = EngineType.Native,
            features = EngineFeatures(typeInference = false, flowAnalysis = true),
        )

        val (type, features) = config

        assertEquals(EngineType.Native, type)
        assertFalse(features.typeInference)
        assertTrue(features.flowAnalysis)
    }

    // =========================================================================
    // EngineFeatures Tests
    // =========================================================================

    @Test
    fun `EngineFeatures defaults are typeInference true, flowAnalysis false`() {
        val features = EngineFeatures()
        assertTrue(features.typeInference)
        assertFalse(features.flowAnalysis)
    }

    @Test
    fun `EngineFeatures equality based on properties`() {
        val features1 = EngineFeatures(typeInference = true, flowAnalysis = false)
        val features2 = EngineFeatures(typeInference = true, flowAnalysis = false)
        val features3 = EngineFeatures(typeInference = false, flowAnalysis = false)

        assertEquals(features1, features2)
        assertNotEquals(features1, features3)
    }

    @Test
    fun `EngineFeatures copy works correctly`() {
        val original = EngineFeatures(typeInference = true, flowAnalysis = false)
        val copied = original.copy(flowAnalysis = true)

        assertFalse(original.flowAnalysis)
        assertTrue(copied.flowAnalysis)
        assertEquals(original.typeInference, copied.typeInference)
    }

    @Test
    fun `EngineFeatures all disabled`() {
        val features = EngineFeatures(typeInference = false, flowAnalysis = false)
        assertFalse(features.typeInference)
        assertFalse(features.flowAnalysis)
    }

    @Test
    fun `EngineFeatures all enabled`() {
        val features = EngineFeatures(typeInference = true, flowAnalysis = true)
        assertTrue(features.typeInference)
        assertTrue(features.flowAnalysis)
    }

    @Test
    fun `EngineFeatures hashCode is consistent`() {
        val features1 = EngineFeatures(typeInference = true, flowAnalysis = true)
        val features2 = EngineFeatures(typeInference = true, flowAnalysis = true)

        assertEquals(features1.hashCode(), features2.hashCode())
    }

    @Test
    fun `EngineFeatures toString includes properties`() {
        val features = EngineFeatures(typeInference = true, flowAnalysis = true)
        val str = features.toString()

        assertTrue(str.contains("EngineFeatures"))
        assertTrue(str.contains("typeInference"))
        assertTrue(str.contains("flowAnalysis"))
    }

    @Test
    fun `EngineFeatures destructuring works`() {
        val features = EngineFeatures(typeInference = false, flowAnalysis = true)
        val (typeInference, flowAnalysis) = features

        assertFalse(typeInference)
        assertTrue(flowAnalysis)
    }
}
