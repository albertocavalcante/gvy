package com.github.albertocavalcante.groovylsp.buildtool.bsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BspConnectionDetailsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parseJson parses valid bazel-bsp connection file`() {
        val json = """
            {
              "name": "Bazel BSP",
              "version": "3.2.0",
              "bspVersion": "2.1.0",
              "languages": ["scala", "java", "kotlin"],
              "argv": ["bazel-bsp"]
            }
        """.trimIndent()

        val details = BspConnectionDetails.parseJson(json)

        assertNotNull(details)
        assertEquals("Bazel BSP", details!!.name)
        assertEquals("3.2.0", details.version)
        assertEquals("2.1.0", details.bspVersion)
        assertEquals(listOf("scala", "java", "kotlin"), details.languages)
        assertEquals(listOf("bazel-bsp"), details.argv)
    }

    @Test
    fun `parseJson parses valid sbt connection file`() {
        val json = """
            {
              "name": "sbt",
              "version": "1.9.7",
              "bspVersion": "2.1.0-M1",
              "languages": ["scala"],
              "argv": ["java", "-Xms100m", "-Xmx100m", "-jar", "/path/to/sbt-launch.jar", "bspListen"]
            }
        """.trimIndent()

        val details = BspConnectionDetails.parseJson(json)

        assertNotNull(details)
        assertEquals("sbt", details!!.name)
        assertEquals("1.9.7", details.version)
        assertEquals(
            listOf("java", "-Xms100m", "-Xmx100m", "-jar", "/path/to/sbt-launch.jar", "bspListen"),
            details.argv,
        )
    }

    @Test
    fun `parseJson parses valid mill connection file`() {
        val json = """
            {
              "name": "mill-bsp",
              "version": "0.11.0",
              "bspVersion": "2.0.0",
              "languages": ["scala", "java"],
              "argv": ["mill", "--bsp"]
            }
        """.trimIndent()

        val details = BspConnectionDetails.parseJson(json)

        assertNotNull(details)
        assertEquals("mill-bsp", details!!.name)
        assertEquals(listOf("mill", "--bsp"), details.argv)
    }

    @Test
    fun `parseJson returns null for missing required fields`() {
        val jsonMissingName = """
            {
              "version": "1.0.0",
              "bspVersion": "2.1.0",
              "argv": ["server"]
            }
        """.trimIndent()

        assertNull(BspConnectionDetails.parseJson(jsonMissingName))
    }

    @Test
    fun `parseJson returns null for empty argv`() {
        val json = """
            {
              "name": "Test",
              "version": "1.0.0",
              "bspVersion": "2.1.0",
              "languages": [],
              "argv": []
            }
        """.trimIndent()

        assertNull(BspConnectionDetails.parseJson(json))
    }

    @Test
    fun `parseJson handles empty languages array`() {
        val json = """
            {
              "name": "Test",
              "version": "1.0.0",
              "bspVersion": "2.1.0",
              "languages": [],
              "argv": ["test-server"]
            }
        """.trimIndent()

        val details = BspConnectionDetails.parseJson(json)

        assertNotNull(details)
        assertEquals(emptyList<String>(), details!!.languages)
    }

    @Test
    fun `findConnectionFiles returns empty list when no bsp directory`() {
        val files = BspConnectionDetails.findConnectionFiles(tempDir)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `findConnectionFiles finds json files in bsp directory`() {
        val bspDir = tempDir.resolve(".bsp").createDirectories()
        bspDir.resolve("bazelbsp.json").writeText("{}")
        bspDir.resolve("sbt.json").writeText("{}")
        bspDir.resolve("not-json.txt").writeText("ignored")

        val files = BspConnectionDetails.findConnectionFiles(tempDir)

        assertEquals(2, files.size)
        assertTrue(files.all { it.toString().endsWith(".json") })
    }

    @Test
    fun `findFirst returns first valid connection`() {
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

        val details = BspConnectionDetails.findFirst(tempDir)

        assertNotNull(details)
        assertEquals("Bazel BSP", details!!.name)
    }

    @Test
    fun `findFirst returns null when no valid connections`() {
        val bspDir = tempDir.resolve(".bsp").createDirectories()
        bspDir.resolve("invalid.json").writeText("not valid json")

        val details = BspConnectionDetails.findFirst(tempDir)

        assertNull(details)
    }

    @Test
    fun `parse returns null for non-existent file`() {
        val nonExistent = tempDir.resolve("does-not-exist.json")
        val details = BspConnectionDetails.parse(nonExistent)
        assertNull(details)
    }
}
