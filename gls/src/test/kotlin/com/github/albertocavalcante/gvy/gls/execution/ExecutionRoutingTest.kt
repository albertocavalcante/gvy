// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExecutionRoutingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `workspace selection chooses the deepest containing folder`() {
        val root = Path.of("/workspace")
        val nested = root.resolve("nested")
        assertEquals(nested, workspaceForFile(nested.resolve("test.groovy"), listOf(root, nested)))
        assertEquals(null, workspaceForFile(Path.of("/outside/file.groovy"), listOf(root, nested)))
    }

    @Test
    fun `a symlink to an outside file is not inside the workspace`() {
        val root = Files.createDirectory(directory.resolve("workspace"))
        val outside = Files.writeString(directory.resolve("outside.groovy"), "println 1")
        val link = root.resolve("linked.groovy")
        val created = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        assumeTrue(created)
        assertEquals(null, workspaceForFile(link, listOf(root)))
    }

    @Test
    fun `conventional roots in a secondary workspace are model candidates`() {
        val sourceRoot = Files.createDirectories(directory.resolve("app/src/custom/groovy"))
        val source = Files.writeString(sourceRoot.resolve("Main.groovy"), "println 1")
        assertTrue(isConventionalGroovyCandidate(source, directory))
        assertFalse(isConventionalGroovyCandidate(directory.resolve("hello.groovy"), directory))
    }

    @Test
    fun `missing previously observed owner is not silently treated as scratch`() {
        val file = directory.resolve("old.groovy")
        assertTrue(previousOwnerMissing(file, setOf(file), emptySet()))
        assertFalse(previousOwnerMissing(file, setOf(file), setOf(file)))
        assertFalse(previousOwnerMissing(file, emptySet(), emptySet()))
    }

    @Test
    fun `project Java home is independent of program environment`() {
        val configured = Files.createDirectories(directory.resolve("configured/bin")).parent
        val override = Files.createDirectories(directory.resolve("override/bin")).parent
        val executable = if (System.getProperty(
                "os.name",
            ).startsWith("Windows", ignoreCase = true)
        ) {
            "java.exe"
        } else {
            "java"
        }
        Files.createFile(configured.resolve("bin").resolve(executable))
        Files.createFile(override.resolve("bin").resolve(executable))
        val options = ExecutionOptions(
            projectJavaHome = configured.toString(),
            environment = mapOf("JAVA_HOME" to override.toString()),
        )
        assertEquals(configured, projectJavaHome(options).getOrThrow())
        assertEquals(configured, projectJavaHome(options.copy(environment = emptyMap())).getOrThrow())
        assertTrue(projectJavaHome(options.copy(projectJavaHome = "relative", environment = emptyMap())).isFailure)
    }

    @Test
    fun `nested settings define an unsupported build boundary`() {
        val nested = Files.createDirectories(directory.resolve("included"))
        val sourceDir = Files.createDirectories(nested.resolve("src/main/groovy"))
        val source = Files.writeString(sourceDir.resolve("Main.groovy"), "println 1")
        Files.writeString(nested.resolve("settings.gradle.kts"), "")
        assertEquals(nested.toRealPath(), nestedBuildBoundary(source, directory))
        Files.writeString(directory.resolve("settings.gradle"), "")
        assertEquals(null, nestedBuildBoundary(directory.resolve("hello.groovy"), directory))
    }

    @Test
    fun `root scratch skips model and candidate or exact owner requires it`() {
        val root = Path.of("/workspace")
        val scratch = root.resolve("hello.groovy")
        val candidate = root.resolve("src/main/groovy/Test.groovy")
        val roots = listOf(root.resolve("src/main/groovy"))
        assertFalse(needsExecutionModel(scratch, roots, emptySet(), false))
        assertTrue(needsExecutionModel(candidate, roots, emptySet(), false))
        assertTrue(needsExecutionModel(scratch, roots, setOf(scratch), false))
        assertTrue(needsExecutionModel(scratch, roots, emptySet(), true))
    }
}
