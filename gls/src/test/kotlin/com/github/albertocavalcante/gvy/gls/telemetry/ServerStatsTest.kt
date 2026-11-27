// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.telemetry

import com.github.albertocavalcante.gvy.gls.BuildInfo
import com.google.gson.Gson
import com.google.gson.JsonElement
import org.junit.jupiter.api.Test
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerStatsTest {
    private val build = BuildInfo.fromProperties(
        Properties().apply {
            setProperty("version", "0.5.4-SNAPSHOT")
            setProperty("displayVersion", "0.5.4-dev+g1234567")
            setProperty("gitSha", "a".repeat(40))
            setProperty("gitDirty", "false")
        },
    )

    private fun snapshot(
        phases: Map<StartupPhase, PhaseRecord> = emptyMap(),
        observedNanos: Long = 0,
        uptimeMs: Long? = null,
    ) = StartupSnapshot(uptimeMs, observedNanos, phases)

    @Test
    fun `stats carry schema one the build stamp and every phase in vocabulary order`() {
        val stats = serverStats(build, snapshot(uptimeMs = 250, observedNanos = 90 * NANOS_PER_MILLI))

        assertEquals(1, stats.schemaVersion)
        assertEquals("known", stats.build.versionState)
        assertEquals("0.5.4-dev+g1234567", stats.build.version)
        assertEquals(StartupPhase.entries.map { it.wireName }, stats.startup.phases.map { it.name })
        assertEquals(250, stats.startup.serverStartJvmUptimeMs)
        assertEquals(90, stats.startup.observedOffsetMs)
    }

    @Test
    fun `pending running and skipped phases leave out the numbers they do not have`() {
        val stats = serverStats(
            build,
            snapshot(
                mapOf(
                    StartupPhase.LSP_INITIALIZE to PhaseRecord(PhaseStatus.RUNNING, 3 * NANOS_PER_MILLI, null),
                    StartupPhase.WORKSPACE_SETUP to PhaseRecord(PhaseStatus.SKIPPED, null, null),
                ),
            ),
        )

        val byName = stats.startup.phases.associateBy { it.name }
        assertEquals(StartupPhaseStats("lsp.initialize", "running", 3, null), byName["lsp.initialize"])
        assertEquals(StartupPhaseStats("workspace.setup", "skipped", null, null), byName["workspace.setup"])
        assertEquals(StartupPhaseStats("dependencies.resolve", "pending", null, null), byName["dependencies.resolve"])
    }

    @Test
    fun `milliseconds are rounded down from nanoseconds`() {
        val stats = serverStats(
            build,
            snapshot(
                mapOf(
                    StartupPhase.WORKSPACE_INDEX to
                        PhaseRecord(PhaseStatus.COMPLETED, startNanos = 1_999_999, endNanos = 5_000_000),
                ),
                observedNanos = 7_999_999,
            ),
        )

        val index = stats.startup.phases.single { it.name == "workspace.index" }
        assertEquals(1, index.startOffsetMs)
        assertEquals(3, index.durationMs)
        assertEquals(7, stats.startup.observedOffsetMs)
    }

    @Test
    fun `an unknown JVM uptime is left out of the JSON`() {
        val json = Gson().toJsonTree(serverStats(build, snapshot(uptimeMs = null))).asJsonObject

        assertNull(json.getAsJsonObject("startup").get("serverStartJvmUptimeMs"))
    }

    @Test
    fun `the JSON holds only vocabulary words and numbers whatever the build stamp says`() {
        val hostile = BuildInfo.fromProperties(
            Properties().apply {
                setProperty("version", "/home/u/ws")
                setProperty("displayVersion", "/home/u/ws")
                setProperty("gitSha", "file:///x")
                setProperty("gitBranch", "C:\\Users\\u\\branch")
            },
        )
        val everyStatus = PhaseStatus.entries.zip(StartupPhase.entries).associate { (status, phase) ->
            phase to PhaseRecord(status, NANOS_PER_MILLI, 2 * NANOS_PER_MILLI)
        }

        val json = Gson().toJsonTree(serverStats(hostile, snapshot(everyStatus, uptimeMs = 10)))

        val allowed = vocabulary()
        val pathLike = Regex("""[/\\]|://|^[A-Za-z]:|^~|file:""")
        val keys = mutableListOf<String>()
        val strings = mutableListOf<String>()
        collect(json, keys, strings)

        assertTrue(keys.isNotEmpty() && strings.isNotEmpty(), "the walk must reach the JSON content")
        assertTrue(keys.none(pathLike::containsMatchIn), "path-like key in $keys")
        assertTrue(strings.none(pathLike::containsMatchIn), "path-like value in $strings")
        assertEquals(emptyList<String>(), strings.filterNot { it in allowed }, "a value outside the vocabulary")
    }

    @Test
    fun `a real shaped build stamp adds only its pattern checked version and commit to the vocabulary`() {
        val everyStatus = PhaseStatus.entries.zip(StartupPhase.entries).associate { (status, phase) ->
            phase to PhaseRecord(status, NANOS_PER_MILLI, 2 * NANOS_PER_MILLI)
        }

        val json = Gson().toJsonTree(serverStats(build, snapshot(everyStatus, uptimeMs = 10)))

        val strings = mutableListOf<String>()
        collect(json, mutableListOf(), strings)
        val version = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT|-dev\\+(?:unknown|g[0-9a-fA-F]{7,64}(?:\\.dirty)?))?")
        val commit = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
        val unexpected = strings.filterNot { it in vocabulary() || version.matches(it) || commit.matches(it) }

        assertEquals(emptyList<String>(), unexpected)
        assertTrue("0.5.4-dev+g1234567" in strings && "a".repeat(40) in strings, "the build stamp must be reported")
    }

    private fun vocabulary() = StartupPhase.entries.map { it.wireName } +
        PhaseStatus.entries.map { it.wireName } +
        listOf("known", "unknown", "malformed")

    private fun collect(element: JsonElement, keys: MutableList<String>, strings: MutableList<String>) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                keys += key
                collect(value, keys, strings)
            }
            element.isJsonArray -> element.asJsonArray.forEach { collect(it, keys, strings) }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> strings += element.asString
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
