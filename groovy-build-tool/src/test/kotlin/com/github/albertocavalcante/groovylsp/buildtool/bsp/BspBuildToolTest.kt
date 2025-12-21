package com.github.albertocavalcante.groovylsp.buildtool.bsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `resolve returns empty resolution when no bsp connection`() {
        val resolution = bspBuildTool.resolve(tempDir)

        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())
    }

    @Test
    fun `resolve returns empty resolution when connection file is invalid`() {
        val bspDir = tempDir.resolve(".bsp").createDirectories()
        bspDir.resolve("invalid.json").writeText("not valid json")

        val resolution = bspBuildTool.resolve(tempDir)

        assertTrue(resolution.dependencies.isEmpty())
        assertTrue(resolution.sourceDirectories.isEmpty())
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
