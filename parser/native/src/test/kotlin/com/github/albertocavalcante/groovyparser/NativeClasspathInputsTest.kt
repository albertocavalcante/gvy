// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.NativeClasspathInputs
import com.github.albertocavalcante.nativeapi.ParseRequest
import kotlinx.coroutines.CancellationException
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeClasspathInputsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `ordered classpath and roots are detached from caller collections without normalization`() {
        val entries = mutableListOf(Path.of("relative/a.jar"), Path.of("relative/b.jar"), Path.of("relative/a.jar"))
        val roots = mutableListOf(Path.of("relative/src"))
        val inputs = NativeClasspathInputs(entries, roots)
        entries.clear()
        roots.clear()

        assertEquals(
            listOf(Path.of("relative/a.jar"), Path.of("relative/b.jar"), Path.of("relative/a.jar")),
            inputs.classpath,
        )
        assertEquals(listOf(Path.of("relative/src")), inputs.sourceRoots)
    }

    @Test
    fun `structural parsing copies inputs without walking the class directory or selecting an SDK`() {
        val classes = directory.resolve("classes")
        Files.createDirectories(classes)
        repeat(64) { Files.writeString(classes.resolve("entry-$it"), "class metadata") }
        var checks = 0
        val result = GroovyParserFacade().parse(
            ParseRequest(
                URI.create("file:///Example.groovy"),
                "class Example { Missing value }",
                classpath = listOf(classes),
                compilePhase = Phases.CONVERSION,
                checkCancelled = { checks++ },
            ),
        )
        result.compilationUnit.classLoader.use {
            val inputs = assertNotNull(result.classpathInputs)
            assertEquals(listOf(classes), inputs.classpath)
            assertEquals(listOf(null), inputs.classpathStamps)
            assertNull(inputs.bootJdk)
            assertNull(inputs.compilerSdk)
            assertNull(result.compilationUnit.classLoader.getResource("groovy/lang/GroovyObject.class"))
            assertTrue(checks < 64, "Structural parsing must not check every class-directory entry: $checks")
        }
    }

    @Test
    fun `parse freezes inputs before cancellation callbacks and compiler configuration`() {
        val entries = mutableListOf(Path.of("relative/a.jar"))
        val roots = mutableListOf(Path.of("relative/src"))
        val result = GroovyParserFacade().parse(
            ParseRequest(
                URI.create("file:///Example.groovy"),
                "class Example {}",
                classpath = entries,
                sourceRoots = roots,
                checkCancelled = {
                    entries.clear()
                    roots.clear()
                },
            ),
        )

        val inputs = assertNotNull(result.classpathInputs)
        assertEquals(listOf(Path.of("relative/a.jar")), inputs.classpath)
        assertEquals(listOf(Path.of("relative/src")), inputs.sourceRoots)
    }

    @Test
    fun `bounded missing-class recovery retains the original ordered classpath inputs`() {
        val result = GroovyParserFacade().parse(
            ParseRequest(
                URI.create("file:///Example.groovy"),
                "class Example { Missing field }",
                classpath = listOf(Path.of("relative/a.jar")),
                compilePhase = Phases.CANONICALIZATION,
            ),
        )

        assertTrue(result.outcome.stubbedClasses.isNotEmpty())
        assertEquals(listOf(Path.of("relative/a.jar")), assertNotNull(result.classpathInputs).classpath)
    }

    @Test
    fun `captured ordered stamps are detached and detect later replacement at the same path`() {
        val first = directory.resolve("one.jar").also { Files.writeString(it, "one") }
        val second = directory.resolve("two.jar").also { Files.writeString(it, "two") }
        val paths = mutableListOf(first, second, first)
        val captured = NativeClasspathInputs.capture(paths, emptyList())
        paths.clear()
        assertEquals(3, captured.classpathStamps.size)
        assertEquals(captured.classpathStamps[0], captured.classpathStamps[2])
        val observed = assertNotNull(captured.classpathStamps[0])
        Files.writeString(first, "replacement with different content length")
        assertNotEquals(observed, NativeArtifactStamp.observe(first))
        assertEquals(3L, observed.size)
    }

    @Test
    fun `directory observations include descendants and never publish over-budget partial evidence`() {
        val root = directory.resolve("classes")
        Files.createDirectories(root)
        Files.writeString(root.resolve("One.class"), "one")
        Files.writeString(root.resolve("Two.class"), "two")
        val stamp = assertNotNull(NativeArtifactStamp.observe(root))
        assertEquals(2L, stamp.files)
        assertEquals(3, stamp.observedPaths)
        assertNull(NativeArtifactStamp.observe(root, maxPaths = 2))
        assertNull(NativeArtifactStamp.observe(directory.resolve("missing.jar")))
    }

    @Test
    fun `one input observation budget covers every directory and entry`() {
        val first = directory.resolve("one")
        val second = directory.resolve("two")
        Files.createDirectories(first)
        Files.createDirectories(second)
        Files.writeString(first.resolve("One.class"), "one")
        Files.writeString(second.resolve("Two.class"), "two")
        val inputs = NativeClasspathInputs.capture(listOf(first, second), emptyList(), maxObservedPaths = 3)
        assertNotNull(inputs.classpathStamps[0])
        assertNull(inputs.classpathStamps[1])
    }

    @Test
    fun `native observation does not swallow cancellation`() {
        assertFailsWith<CancellationException> {
            NativeArtifactStamp.observe(directory, checkCancelled = { throw CancellationException("stop") })
        }
    }

    @Test
    fun `compiler retries preserve stamps captured before configuration opens a replaced jar`() {
        val archive = directory.resolve("dependency.jar")
        writeJar(archive, "one")
        val before = assertNotNull(NativeArtifactStamp.observe(archive))
        var checks = 0
        val result = GroovyParserFacade().parse(
            ParseRequest(
                URI.create("file:///Example.groovy"),
                "class Example { Missing field }",
                classpath = listOf(archive),
                compilePhase = Phases.CANONICALIZATION,
                checkCancelled = { if (++checks == 2) writeJar(archive, "a longer replacement artifact") },
            ),
        )
        try {
            assertTrue(result.outcome.stubbedClasses.isNotEmpty())
            assertEquals(listOf(before), assertNotNull(result.classpathInputs).classpathStamps)
            assertNotEquals(before, NativeArtifactStamp.observe(archive))
        } finally {
            result.compilationUnit.classLoader.close()
        }
    }

    private fun writeJar(path: Path, marker: String) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("marker"))
            jar.write(marker.toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
    }
}
