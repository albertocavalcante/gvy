// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.bsp

import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import com.github.albertocavalcante.gvy.build.ResolutionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BspBuildToolTest {

    @TempDir
    lateinit var tempDir: Path

    private val bspBuildTool = BspBuildTool()

    @Test
    fun `name returns BSP`() {
        assertEquals("BSP", bspBuildTool.name)
    }

    @Test
    fun `canHandle returns false when no bsp directory exists`() {
        assertFalse(bspBuildTool.canHandle(tempDir))
    }

    @Test
    fun `canHandle returns false when bsp directory is empty`() {
        tempDir.resolve(".bsp").createDirectories()
        assertFalse(bspBuildTool.canHandle(tempDir))
    }

    @Test
    fun `canHandle returns true when valid bsp connection file exists`() {
        val bspDir = tempDir.resolve(".bsp").createDirectories()
        bspDir.resolve("bazelbsp.json").writeText(
            """
            {
              "name": "Bazel BSP",
              "version": "3.2.0",
              "bspVersion": "2.1.0",
              "languages": ["java"],
              "argv": ["bazel-bsp"]
            }
            """.trimIndent(),
        )

        assertTrue(bspBuildTool.canHandle(tempDir))
    }

    @Test
    fun `canHandle returns false when bsp directory only has non-json files`() {
        val bspDir = tempDir.resolve(".bsp").createDirectories()
        bspDir.resolve("readme.txt").writeText("Not a connection file")

        assertFalse(bspBuildTool.canHandle(tempDir))
    }

    @Test
    fun `resolve fails when no bsp connection is available`() {
        val resolution = bspBuildTool.resolve(tempDir)

        val failure = assertInstanceOf(ResolutionStatus.Failed::class.java, resolution.status)
        assertEquals("BSP_CONNECTION_NOT_FOUND", failure.code)
        assertFalse(resolution.isUsable)
    }

    @Test
    fun `resolve fails when connection file is invalid`() {
        val bspDir = tempDir.resolve(".bsp").createDirectories()
        bspDir.resolve("invalid.json").writeText("not valid json")

        val resolution = bspBuildTool.resolve(tempDir)

        val failure = assertInstanceOf(ResolutionStatus.Failed::class.java, resolution.status)
        assertEquals("BSP_CONNECTION_NOT_FOUND", failure.code)
        assertFalse(resolution.isUsable)
    }

    @Test
    fun `resolve fails when the bsp client cannot connect`() {
        val client = mockk<BspClient>(relaxed = true)
        every { client.connect(any()) } returns false
        val resolution = toolWithClient(client).resolve(tempDir)

        val failure = assertInstanceOf(ResolutionStatus.Failed::class.java, resolution.status)
        assertEquals("BSP_CONNECTION_FAILED", failure.code)
        assertFalse(resolution.isUsable)
        verify(exactly = 0) { client.workspaceBuildTargets() }
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `resolve fails when target discovery fails`() {
        val client = mockk<BspClient>(relaxed = true)
        every { client.connect(any()) } returns true
        every { client.workspaceBuildTargets() } returns null
        val resolution = toolWithClient(client).resolve(tempDir)

        assertEquals(
            "BSP_TARGET_DISCOVERY_FAILED",
            assertInstanceOf(ResolutionStatus.Failed::class.java, resolution.status).code,
        )
        assertFalse(resolution.isUsable)
        verify(exactly = 0) { client.buildTargetSources(any()) }
        verify(exactly = 0) { client.buildTargetDependencyModules(any()) }
        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `successful empty target response remains a usable empty resolution`() {
        val client = mockk<BspClient>(relaxed = true)
        every { client.connect(any()) } returns true
        every { client.workspaceBuildTargets() } returns WorkspaceBuildTargetsResult(emptyList())
        val resolution = toolWithClient(client).resolve(tempDir)

        assertEquals(ResolutionStatus.Success, resolution.status)
        assertTrue(resolution.isUsable)
        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())
        verify(exactly = 0) { client.buildTargetSources(any()) }
        verify(exactly = 0) { client.buildTargetDependencyModules(any()) }
        verify(exactly = 1) { client.close() }
    }

    private fun toolWithClient(client: BspClient): BspBuildTool {
        tempDir.resolve(".bsp").createDirectories().resolve("test.json").writeText(
            """{"name":"test","version":"1","bspVersion":"2.1.0","languages":["groovy"],"argv":["unused"]}""",
        )
        return BspBuildTool { _, _ -> client }
    }

    // NOTE: Integration tests with real BSP servers (bazel-bsp, sbt) would require
    // actual build tool installations. Those tests should be run manually or in CI
    // with appropriate setup.
    //
    // To test manually:
    // 1. Create a Bazel project and run: bazel run @bazel_bsp//:install
    // 2. Or create an sbt project (sbt 1.4+) - BSP is built-in
    // 3. Or create a Mill project and run: mill mill.bsp.BSP/install
}
