// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.maven

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MavenBuildToolCliTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parseClasspath drops blank entries instead of resolving them to the working directory`() {
        val tool = MavenBuildTool()
        val jar = tempDir.resolve("a.jar").also { it.writeText("x") }

        assertTrue(tool.parseClasspath("").isEmpty())
        assertTrue(tool.parseClasspath("  \n").isEmpty())
        assertEquals(listOf(jar), tool.parseClasspath("${jar}${File.pathSeparator}${File.pathSeparator}"))
    }

    @Test
    fun `resolveViaCli returns nothing when mvn exits 0 without writing the classpath`() {
        assumeFalse(System.getProperty("os.name", "").lowercase().contains("windows"), "needs a POSIX mvnw")
        val wrapper = tempDir.resolve("mvnw").also {
            it.writeText("#!/bin/sh\nexit 0\n")
            assertTrue(it.toFile().setExecutable(true))
        }
        assertTrue(Files.isExecutable(wrapper))

        assertTrue(MavenBuildTool().resolveViaCli(tempDir).isEmpty())
    }

    @Test
    fun `resolveViaCli survives temp file creation and deletion failures`() {
        val tool = MavenBuildTool()

        assertTrue(tool.resolveViaCli(tempDir, createTempFile = { throw IOException("disk full") }).isEmpty())

        val wrapper = tempDir.resolve("mvnw")
        assumeFalse(System.getProperty("os.name", "").lowercase().contains("windows"), "needs a POSIX mvnw")
        val jar = tempDir.resolve("b.jar").also { it.writeText("x") }
        // Fake mvn: write the classpath into the -Dmdep.outputFile argument.
        val script = """
            #!/bin/sh
            for a in "${'$'}@"; do
              case "${'$'}a" in
                -Dmdep.outputFile=*) echo "$jar" > "${'$'}{a#*=}" ;;
              esac
            done
        """.trimIndent()
        wrapper.writeText(script + "\n")
        assertTrue(wrapper.toFile().setExecutable(true))

        val result = tool.resolveViaCli(tempDir, deleteFile = { throw IOException("locked") })

        assertEquals(listOf(jar), result)
    }
}
