package com.github.albertocavalcante.groovylsp.engine.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [EngineType] and [EngineConfiguration].
 */
class EngineConfigurationTest {

    @Test
    fun `EngineType fromString parses native case-insensitively`() {
        assertEquals(EngineType.Native, EngineType.fromString("native"))
        assertEquals(EngineType.Native, EngineType.fromString("NATIVE"))
        assertEquals(EngineType.Native, EngineType.fromString("Native"))
    }

    @Test
    fun `EngineType fromString parses core case-insensitively`() {
        assertEquals(EngineType.Core, EngineType.fromString("core"))
        assertEquals(EngineType.Core, EngineType.fromString("CORE"))
        assertEquals(EngineType.Core, EngineType.fromString("Core"))
    }

    @Test
    fun `EngineType fromString parses openrewrite case-insensitively`() {
        assertEquals(EngineType.OpenRewrite, EngineType.fromString("openrewrite"))
        assertEquals(EngineType.OpenRewrite, EngineType.fromString("OPENREWRITE"))
        assertEquals(EngineType.OpenRewrite, EngineType.fromString("OpenRewrite"))
    }

    @Test
    fun `EngineType fromString defaults to Native for unknown values`() {
        assertEquals(EngineType.Native, EngineType.fromString("unknown"))
        assertEquals(EngineType.Native, EngineType.fromString(""))
        assertEquals(EngineType.Native, EngineType.fromString("invalid"))
    }

    @Test
    fun `EngineType fromString defaults to Native for null`() {
        assertEquals(EngineType.Native, EngineType.fromString(null))
    }

    @Test
    fun `EngineType entries contains all types`() {
        val entries = EngineType.entries
        assertEquals(3, entries.size)
        assertTrue(entries.contains(EngineType.Native))
        assertTrue(entries.contains(EngineType.Core))
        assertTrue(entries.contains(EngineType.OpenRewrite))
    }

    @Test
    fun `EngineType id returns correct identifier`() {
        assertEquals("native", EngineType.Native.id)
        assertEquals("core", EngineType.Core.id)
        assertEquals("openrewrite", EngineType.OpenRewrite.id)
    }

    @Test
    fun `exhaustive when compiles for EngineType`() {
        // This test documents that the compiler enforces exhaustive when expressions.
        // If a new EngineType is added without updating this function, it won't compile.
        fun describe(type: EngineType): String = when (type) {
            EngineType.Native -> "native engine using Groovy compiler AST"
            EngineType.Core -> "core engine using groovyparser-core AST"
            EngineType.OpenRewrite -> "openrewrite engine using LST"
        }
        assertEquals("native engine using Groovy compiler AST", describe(EngineType.Native))
        assertEquals("core engine using groovyparser-core AST", describe(EngineType.Core))
        assertEquals("openrewrite engine using LST", describe(EngineType.OpenRewrite))
    }

    @Test
    fun `EngineConfiguration has sensible defaults`() {
        val config = EngineConfiguration()
        assertEquals(EngineType.Native, config.type)
        assertTrue(config.features.typeInference)
        assertFalse(config.features.flowAnalysis)
    }

    @Test
    fun `EngineConfiguration can be created with custom values`() {
        val features = EngineFeatures(typeInference = false, flowAnalysis = true)
        val config = EngineConfiguration(type = EngineType.Core, features = features)
        assertEquals(EngineType.Core, config.type)
        assertFalse(config.features.typeInference)
        assertTrue(config.features.flowAnalysis)
    }
}
