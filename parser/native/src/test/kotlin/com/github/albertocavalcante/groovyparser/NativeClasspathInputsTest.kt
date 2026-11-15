// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.NativeClasspathInputs
import com.github.albertocavalcante.nativeapi.ParseMode
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.directoryIdentity
import kotlinx.coroutines.CancellationException
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
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
    fun `legacy artifact stamp constructor remains available`() {
        val constructor = NativeArtifactStamp::class.java.getConstructor(
            checkNotNull(Long::class.javaPrimitiveType),
            checkNotNull(Long::class.javaPrimitiveType),
            Long::class.javaObjectType,
            checkNotNull(Int::class.javaPrimitiveType),
        )

        assertEquals(
            NativeArtifactStamp(10, 20, 3, 4),
            constructor.newInstance(10L, 20L, 3L, 4),
        )
    }

    @Test
    fun `parse request preserves positional order of source callbacks`() {
        val candidate = Path.of("src/Example.groovy")
        val request = ParseRequest(
            URI.create("file:///Example.groovy"),
            "class Example {}",
            emptyList(),
            emptyList(),
            emptyList(),
            emptyMap(),
            emptySet(),
            emptySet(),
            Phases.CONVERSION,
            ParseMode.WORKSPACE,
            {},
            { listOf(candidate) },
            { it == candidate },
        )

        assertEquals(listOf(candidate), request.workspaceClassLocator?.invoke("Example"))
        assertTrue(request.sourcePathAdmission?.invoke(candidate) == true)
        val copied = request.copy(content = "class Example {}")
        assertTrue(copied.sourcePathAdmission?.invoke(candidate) == true)
    }

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
        result.compilationUnit.classLoader.use {
            val inputs = assertNotNull(result.classpathInputs)
            assertEquals(listOf(Path.of("relative/a.jar")), inputs.classpath)
            assertEquals(listOf(Path.of("relative/src")), inputs.sourceRoots)
        }
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
        result.compilationUnit.classLoader.use {
            assertTrue(result.outcome.stubbedClasses.isNotEmpty())
            assertEquals(listOf(Path.of("relative/a.jar")), assertNotNull(result.classpathInputs).classpath)
        }
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
    fun `directory identity does not depend on traversal order`() {
        val first = linkedMapOf("b" to listOf("2"), "a" to listOf("1"))
        val second = linkedMapOf("a" to listOf("1"), "b" to listOf("2"))
        assertEquals(directoryIdentity(first), directoryIdentity(second))
    }

    @Test
    fun `metadata-only observations avoid content hashing on a provider without change times`() {
        val archive = directory.resolve("metadata-provider.zip")
        FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fs ->
            val tree = Files.createDirectory(fs.getPath("/classes"))
            val file = Files.writeString(tree.resolve("Example.class"), "original")
            for (path in listOf(file, tree)) {
                val strong = assertNotNull(NativeArtifactStamp.observe(path))
                assertNotNull(strong.contentIdentity)
                val metadata = assertNotNull(
                    NativeArtifactStamp.observe(path, maxContentBytes = 0, includeContentIdentity = false),
                )
                assertEquals(strong.copy(contentIdentity = null), metadata)
                assertNull(NativeArtifactStamp.observe(path, maxContentBytes = 0))
            }
        }
    }

    @Test
    fun `fallback content receipt rejects a file changed during hashing`() {
        val archive = directory.resolve("provider.zip")
        FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fs ->
            val file = fs.getPath("/input.jar")
            Files.writeString(file, "original")
            var checks = 0
            val stamp = NativeArtifactStamp.observe(file, checkCancelled = {
                checks++
                if (checks == 2) Files.writeString(file, "replacement")
            })
            assertNull(stamp)
        }
    }

    @Test
    fun `fallback tree receipt rejects a descendant changed during hashing`() {
        val archive = directory.resolve("tree-provider.zip")
        FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fs ->
            val tree = Files.createDirectory(fs.getPath("/classes"))
            val file = Files.writeString(tree.resolve("Example.class"), "original")
            var checks = 0
            val stamp = NativeArtifactStamp.observe(tree, checkCancelled = {
                checks++
                if (checks == 4) Files.writeString(file, "replacement")
            })
            assertNull(stamp)
        }
    }

    @Test
    fun `archive receipt detects atomic replacement with the same size and modification time`() {
        val archive = directory.resolve("dependency.jar")
        Files.writeString(archive, "original")
        val before = assertNotNull(NativeArtifactStamp.observe(archive))
        val replacement = directory.resolve("replacement.jar")
        Files.writeString(replacement, "replaced")
        Files.setLastModifiedTime(replacement, FileTime.fromMillis(before.lastModifiedMillis))

        try {
            Files.move(
                replacement,
                archive,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(replacement, archive, StandardCopyOption.REPLACE_EXISTING)
        }

        val after = assertNotNull(NativeArtifactStamp.observe(archive))
        assertEquals(before.size, after.size)
        assertEquals(before.lastModifiedMillis, after.lastModifiedMillis)
        assertTrue(
            before.fileKey != after.fileKey || before.creationTime != after.creationTime,
            "the filesystem should expose a changed object identity",
        )
        assertNotEquals(before, after)
    }

    @Test
    fun `directory receipt tracks older entries when the newest timestamp is unchanged`() {
        val classes = Files.createDirectory(directory.resolve("classes"))
        val first = Files.writeString(classes.resolve("First.class"), "one")
        val second = Files.writeString(classes.resolve("Second.class"), "two")
        val now = System.currentTimeMillis()
        Files.setLastModifiedTime(first, FileTime.fromMillis(now + 1_000))
        Files.setLastModifiedTime(second, FileTime.fromMillis(now + 2_000))
        val before = assertNotNull(NativeArtifactStamp.observe(classes))

        Files.writeString(first, "two")
        Files.setLastModifiedTime(first, FileTime.fromMillis(now + 1_500))

        val after = assertNotNull(NativeArtifactStamp.observe(classes))
        assertEquals(before.size, after.size)
        assertEquals(before.lastModifiedMillis, after.lastModifiedMillis)
        assertNotEquals(before.directoryIdentity, after.directoryIdentity)
    }

    @Test
    fun `in-place archive rewrite with restored mtime changes its receipt`() {
        val archive = directory.resolve("dependency.jar")
        Files.writeString(archive, "original")
        val before = assertNotNull(NativeArtifactStamp.observe(archive))
        assumeTrue(before.changeTime != null, "filesystem does not expose POSIX change time")

        Files.writeString(archive, "replaced")
        Files.setLastModifiedTime(archive, FileTime.from(Instant.parse(before.lastModifiedTime)))

        val after = assertNotNull(NativeArtifactStamp.observe(archive))
        assertEquals(before.size, after.size)
        assertEquals(before.lastModifiedTime, after.lastModifiedTime)
        assertNotEquals(before.changeTime, after.changeTime)
        assertNotEquals(before, after)
    }

    @Test
    fun `directory receipt detects same-size descendant rewrite with restored mtime`() {
        val classes = Files.createDirectory(directory.resolve("same-metadata-classes"))
        val first = Files.writeString(classes.resolve("First.class"), "original")
        val before = assertNotNull(NativeArtifactStamp.observe(classes))
        assumeTrue(before.changeTime != null, "filesystem does not expose POSIX change time")
        val originalTime = Files.getLastModifiedTime(first)

        Files.writeString(first, "replaced")
        Files.setLastModifiedTime(first, originalTime)

        val after = assertNotNull(NativeArtifactStamp.observe(classes))
        assertEquals(before.size, after.size)
        assertEquals(before.lastModifiedMillis, after.lastModifiedMillis)
        assertNotEquals(before.directoryIdentity, after.directoryIdentity)
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
