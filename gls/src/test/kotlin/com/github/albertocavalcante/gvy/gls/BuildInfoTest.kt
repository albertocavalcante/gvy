// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import org.junit.jupiter.api.Test
import java.io.StringReader
import java.time.Instant
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildInfoTest {

    private fun props(text: String) = Properties().apply { load(StringReader(text)) }

    private val sha = "6c6f02380e0f8f1d8c0a4e9d2b7f5a1c3e4d5b6a"

    private val cleanProps = """
        version=0.5.4-SNAPSHOT
        baseVersion=0.5.4
        displayVersion=0.5.4-dev+g6c6f023
        release=false
        gitSha=$sha
        gitShaShort=6c6f023
        gitDirty=false
        gitDirtyHash=
        gitBranch=feature/x
        commitTime=2026-11-10T09:30:00-03:00
        buildTime=2026-11-10T09:30:00-03:00
        ciRunUrl=https://github.com/o/r/actions/runs/42
    """.trimIndent()

    @Test
    fun `parses a fully stamped properties file`() {
        val info = BuildInfo.fromProperties(props(cleanProps))

        assertEquals("0.5.4-SNAPSHOT", info.version)
        assertEquals("0.5.4", info.baseVersion)
        assertEquals("0.5.4-dev+g6c6f023", info.displayVersion)
        assertEquals(sha, info.gitSha)
        assertEquals("6c6f023", info.gitShaShort)
        assertEquals(false, info.gitDirty)
        assertEquals("", info.gitDirtyHash)
        assertEquals("feature/x", info.gitBranch)
        assertEquals(Instant.parse("2026-11-10T12:30:00Z"), info.commitTime)
        assertEquals("https://github.com/o/r/actions/runs/42", info.ciRunUrl)
        assertTrue(info.isStamped)
    }

    @Test
    fun `release shape reports the bare base version and is flagged as release`() {
        val info = BuildInfo.fromProperties(
            props(
                "version=0.5.4\nbaseVersion=0.5.4\ndisplayVersion=0.5.4\nrelease=true\n" +
                    "gitSha=$sha\ngitShaShort=6c6f023\ngitDirty=false",
            ),
        )

        assertTrue(info.release)
        assertTrue(info.isStamped)
        assertEquals("0.5.4", info.displayVersion)
        assertFalse(info.displayVersion.contains(info.gitShaShort), "releases carry no sha in the version string")
        assertTrue(info.report().contains(sha))
    }

    @Test
    fun `non-ascii values survive a utf-8 properties round trip`() {
        val text = "gitBranch=feature/caf\u00e9\n"
        val info = BuildInfo.fromProperties(
            Properties().apply { load(text.byteInputStream(Charsets.UTF_8).reader(Charsets.UTF_8)) },
        )
        assertEquals("feature/caf\u00e9", info.gitBranch)
    }

    @Test
    fun `legacy properties without provenance degrade to unknown`() {
        val info = BuildInfo.fromProperties(props("version=0.5.4-SNAPSHOT\nbaseVersion=0.5.4"))

        assertEquals("0.5.4-SNAPSHOT", info.displayVersion, "legacy jars fall back to version")
        assertEquals(BuildInfo.UNKNOWN, info.gitSha)
        assertNull(info.gitDirty)
        assertNull(info.commitTime)
        assertNull(info.ciRunUrl)
        assertFalse(info.isStamped)
    }

    @Test
    fun `empty properties never throw`() {
        val info = BuildInfo.fromProperties(Properties())

        assertEquals(BuildInfo.UNKNOWN, info.version)
        assertEquals(BuildInfo.UNKNOWN, info.displayVersion)
        assertFalse(info.isStamped)
    }

    @Test
    fun `malformed values are treated as unknown rather than failing`() {
        val info = BuildInfo.fromProperties(props("gitDirty=maybe\ncommitTime=yesterday\nbuildTime=unknown"))

        assertNull(info.gitDirty)
        assertNull(info.commitTime)
        assertNull(info.buildTime)
    }

    @Test
    fun `startup line names version commit and tree state`() {
        val clean = BuildInfo.fromProperties(props(cleanProps))
        assertEquals(
            "gls 0.5.4-dev+g6c6f023 (git $sha, tree clean, branch feature/x)",
            clean.startupLine(),
        )

        val dirty = BuildInfo.fromProperties(
            props("$cleanProps\ngitDirty=true\ngitDirtyHash=abc123def456\ndisplayVersion=0.5.4-dev+g6c6f023.dirty"),
        )
        assertTrue(dirty.startupLine().contains("tree dirty (abc123def456)"), dirty.startupLine())
        assertTrue(dirty.startupLine().contains("0.5.4-dev+g6c6f023.dirty"))
    }

    @Test
    fun `report keeps the version token on the first line`() {
        val lines = BuildInfo.fromProperties(props(cleanProps)).report().lines()

        assertEquals("gls version 0.5.4-dev+g6c6f023", lines.first())
        assertTrue(lines.any { it.contains(sha) })
    }

    @Test
    fun `json carries the full sha and null for unknown fields`() {
        val json = BuildInfo.fromProperties(props("version=1.0.0")).toJson().toString()
        assertTrue(json.contains("\"gitSha\":\"unknown\""), json)
        assertTrue(json.contains("\"gitDirty\":null"), json)

        val stamped = BuildInfo.fromProperties(props(cleanProps)).toJson().toString()
        assertTrue(stamped.contains("\"gitSha\":\"$sha\""), stamped)
        assertTrue(stamped.contains("\"commitTime\":\"2026-11-10T12:30:00Z\""), stamped)
    }
}
