// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.verification

import com.github.albertocavalcante.gvy.gls.BuildInfo
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeFactsTest {
    @Test
    fun `runtime facts preserve actual bounded runtime values and valid embedded stamp`() {
        val properties = Properties().apply {
            setProperty("version", "0.5.4-SNAPSHOT")
            setProperty("displayVersion", "0.5.4-dev+g1234567")
            setProperty("gitSha", "a".repeat(40))
            setProperty("gitDirty", "false")
            setProperty("gitBranch", "private-branch")
            setProperty("ciRunUrl", "https://private.example/run")
        }
        val facts = runtimeFacts(
            BuildInfo.fromProperties(properties),
            RuntimeObservation(123, 21, "21.0.5+11", 268435456),
            HoverEngineObservation("native", "not_instantiated", null),
        )
        assertEquals(1, facts.schemaVersion)
        assertEquals(123L, facts.runtime.processId)
        assertEquals(21, facts.runtime.javaFeature)
        assertEquals("21.0.5+11", facts.runtime.javaVersion)
        assertEquals(268435456L, facts.runtime.maxHeapBytes)
        assertEquals("known", facts.build.commitState)
        assertEquals("a".repeat(40), facts.build.commit)
        assertEquals("known", facts.build.dirtyState)
        assertEquals(false, facts.build.dirty)
        assertEquals("not_instantiated", facts.hoverEngine.state)
        assertNull(facts.hoverEngine.instantiated)
        assertTrue(!facts.toString().contains("private-branch"))
        assertTrue(!facts.toString().contains("private.example"))
    }

    @Test
    fun `runtime observation reads the current process rather than a requested configuration`() {
        val observed = observeRuntime()
        assertEquals(ProcessHandle.current().pid(), observed.processId)
        assertEquals(Runtime.version().feature(), observed.javaFeature)
        assertEquals(Runtime.version().toString(), observed.javaVersion)
        assertEquals(Runtime.getRuntime().maxMemory(), observed.maxHeapBytes)
    }

    @Test
    fun `legacy version and supported development stamps stay visible`() {
        val properties = Properties().apply { setProperty("version", "0.5.4-SNAPSHOT") }
        val observed = RuntimeObservation(1, 17, "17.0.1", 1024)
        val engine = HoverEngineObservation("native", "not_instantiated", null)
        assertEquals(
            "0.5.4-SNAPSHOT",
            runtimeFacts(BuildInfo.fromProperties(properties), observed, engine).build.version,
        )
        properties.setProperty("displayVersion", "0.5.4-dev+unknown")
        assertEquals("known", runtimeFacts(BuildInfo.fromProperties(properties), observed, engine).build.versionState)
        properties.setProperty("displayVersion", "0.5.4-dev+gabcdef0.dirty")
        assertEquals("known", runtimeFacts(BuildInfo.fromProperties(properties), observed, engine).build.versionState)
    }

    @Test
    fun `legacy malformed and oversized facts remain explicitly unavailable`() {
        val legacy = runtimeFacts(
            BuildInfo.fromProperties(Properties()),
            RuntimeObservation(null, null, null, null),
            HoverEngineObservation("native", "unknown", null),
        )
        assertEquals("unknown", legacy.build.dirtyState)
        assertEquals("unknown", legacy.build.commitState)
        assertNull(legacy.build.commit)
        assertEquals("unknown", legacy.runtime.processState)
        assertEquals("unknown", legacy.runtime.javaState)
        assertEquals("unknown", legacy.runtime.heapState)
        val properties = Properties().apply {
            setProperty("gitSha", "private/" + "x".repeat(200))
            setProperty("displayVersion", "1.2.3-private-branch")
        }
        val malformed = runtimeFacts(
            BuildInfo.fromProperties(properties),
            RuntimeObservation(-1, 0, "secret/path", Long.MAX_VALUE),
            HoverEngineObservation("native", "unknown", null),
        )
        assertEquals("malformed", malformed.build.commitState)
        assertNull(malformed.build.commit)
        assertEquals("malformed", malformed.build.versionState)
        assertNull(malformed.build.version)
        assertEquals("malformed", malformed.runtime.processState)
        assertEquals("malformed", malformed.runtime.javaState)
        assertEquals("malformed", malformed.runtime.heapState)
        assertTrue(!malformed.toString().contains("private"))
        assertTrue(!malformed.toString().contains("secret"))
    }
}
