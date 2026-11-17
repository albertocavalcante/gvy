// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains

import junit.framework.TestCase
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class GvyLaunchConfigurationTest : TestCase() {
    @Test
    fun `test launch preserves spaces and supplementary Unicode as individual arguments`() =
        withInputs { java, jar ->
            val options =
                GvyProjectSettings.Options(
                    enabled = true,
                    javaExecutable = java.toString(),
                    serverJar = jar.toString(),
                    jvmArguments = "-Xmx1g\n-Dgvy.label=hello 😀 world",
                    serverConfiguration = """{"groovy.codenarc.enabled":false}""",
                )
            val launch = GvyLaunchConfiguration.from(options)
            val expected =
                listOf(
                    java.toString(),
                    "-Xmx1g",
                    "-Dgvy.label=hello 😀 world",
                    "-jar",
                    jar.toString(),
                    "lsp",
                    "stdio",
                )
            assertEquals(expected, launch.command)
            assertFalse(launch.initializationOptions["groovy.codenarc.enabled"].asBoolean)
        }

    @Test
    fun `test disabled untrusted nonlocal and non Groovy files never start`() {
        assertTrue(canStartGvy(true, true, true, "groovy"))
        assertFalse(canStartGvy(false, true, true, "groovy"))
        assertFalse(canStartGvy(true, false, true, "groovy"))
        assertFalse(canStartGvy(true, true, false, "groovy"))
        for (extension in listOf(null, "gradle", "jenkinsfile", "java")) {
            assertFalse(canStartGvy(true, true, true, extension))
        }
    }

    @Test
    fun `test invalid launch selectors and noncanonical configuration are rejected`() =
        withInputs { java, jar ->
            val valid = GvyProjectSettings.Options(javaExecutable = java.toString(), serverJar = jar.toString())
            for (argument in listOf("-jar", "--module=other", "@options.txt", "SomeMain", "--class-path=elsewhere")) {
                expectInvalid { GvyLaunchConfiguration.from(valid.copy(jvmArguments = argument)) }
            }
            for (json in listOf("[]", "invalid", """{"codenarc":false}""")) {
                expectInvalid { GvyLaunchConfiguration.from(valid.copy(serverConfiguration = json)) }
            }
            expectInvalid { GvyLaunchConfiguration.from(valid.copy(javaExecutable = "relative/java")) }
            val missingJar = jar.resolveSibling("missing.jar").toString()
            expectInvalid { GvyLaunchConfiguration.from(valid.copy(serverJar = missingJar)) }
        }

    @Test
    fun `test settings instances and returned state cannot mutate another project`() {
        val first = GvyProjectSettings()
        val second = GvyProjectSettings()
        first.loadState(GvyProjectSettings.Options(enabled = true, javaExecutable = "/first/java"))
        first.state.enabled = false
        assertTrue(first.state.enabled)
        assertFalse(second.state.enabled)
        assertEquals("", second.state.javaExecutable)
    }

    private fun expectInvalid(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid launch configuration")
        } catch (_: IllegalArgumentException) {
            // All invalid UI input is rejected before process startup.
        } catch (_: com.google.gson.JsonParseException) {
            // Invalid JSON is reported by the settings page.
        }
    }

    private fun withInputs(action: (Path, Path) -> Unit) {
        val directory = Files.createTempDirectory("gvy paths 😀 ")
        try {
            val java = Files.createFile(directory.resolve("java executable"))
            assertTrue(java.toFile().setExecutable(true))
            val jar = directory.resolve("server 😀.jar")
            val manifest =
                Manifest().apply {
                    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                    mainAttributes[Attributes.Name.MAIN_CLASS] = "GvyMain"
                }
            JarOutputStream(Files.newOutputStream(jar), manifest).use { }
            action(java.toRealPath(), jar.toRealPath())
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
