package com.github.albertocavalcante.groovyjenkins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class VarsGlobalVariableProviderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should scan vars directory and detect global variables`() {
        val varsDir = tempDir.resolve("vars")
        Files.createDirectory(varsDir)

        val logScript = varsDir.resolve("log.groovy")
        Files.writeString(logScript, "def info(msg) { println msg }")

        val utilsScript = varsDir.resolve("Utils.groovy")
        Files.writeString(utilsScript, "class Utils {}")

        val provider = VarsGlobalVariableProvider(tempDir)
        val vars = provider.getGlobalVariables()

        println("Detected vars: $vars")

        assertEquals(2, vars.size)
        assertTrue(vars.any { it.name == "log" })
        assertTrue(vars.any { it.name == "Utils" })
    }
}
