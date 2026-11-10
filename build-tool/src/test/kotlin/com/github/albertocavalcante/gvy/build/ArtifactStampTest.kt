package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A class directory on the classpath is stamped by what it contains, not by its own attributes, which do not change
 * when a class inside it is rewritten (#1364).
 */
class ArtifactStampTest {
    @TempDir
    lateinit var temporary: Path

    private val classes by lazy { Files.createDirectories(temporary.resolve("classes/com/example")) }

    /** Pins every directory's mtime, so only a file's own change can move the stamp. */
    private fun pinDirectories() {
        var dir: Path? = classes
        while (dir != null && dir.startsWith(temporary)) {
            Files.setLastModifiedTime(dir, FileTime.fromMillis(PINNED))
            dir = dir.parent
        }
    }

    private fun write(name: String, size: Int, modified: Long): Path {
        val file = Files.write(classes.resolve(name), ByteArray(size))
        return Files.setLastModifiedTime(file, FileTime.fromMillis(modified))
    }

    private fun stampOfRoot() = assertNotNull(ArtifactStamp.of(temporary.resolve("classes")))

    @Test
    fun `a class rewritten in place inside a directory changes the directory's stamp`() {
        write("A.class", 3, 1_000_000)
        pinDirectories()
        val before = stampOfRoot()

        write("A.class", 5, 2_000_000)
        pinDirectories()

        assertNotEquals(before, stampOfRoot())
    }

    @Test
    fun `a class rewritten with the same size but a new time changes the stamp`() {
        write("A.class", 3, 1_000_000)
        pinDirectories()
        val before = stampOfRoot()

        write("A.class", 3, 2_000_000)
        pinDirectories()

        assertNotEquals(before, stampOfRoot())
    }

    @Test
    fun `a class deleted from a directory changes the stamp`() {
        write("A.class", 3, 1_000_000)
        write("B.class", 0, 1_000_000)
        pinDirectories()
        val before = stampOfRoot()

        Files.delete(classes.resolve("B.class"))
        pinDirectories()

        assertNotEquals(before, stampOfRoot())
    }

    @Test
    fun `a directory is stamped by the total size, count and newest time of its files`() {
        write("A.class", 3, 1_000_000)
        write("B.class", 4, 2_000_000)
        pinDirectories()

        assertEquals(ArtifactStamp(7, 2_000_000, 2), stampOfRoot())
    }

    @Test
    fun `a directory with more files than the bound has no stamp`() {
        repeat(3) { write("C$it.class", 1, 1_000_000) }
        val root = temporary.resolve("classes")

        assertNull(ArtifactStamp.of(root, maxDirectoryFiles = 2))
        assertEquals(3L, ArtifactStamp.of(root, maxDirectoryFiles = 3)?.files)
    }

    @Test
    fun `a jar is still stamped by its own size and time`() {
        val jar = Files.write(temporary.resolve("lib.jar"), ByteArray(3))
        Files.setLastModifiedTime(jar, FileTime.fromMillis(1_000_000))

        assertEquals(ArtifactStamp(3, 1_000_000), ArtifactStamp.of(jar))
    }

    @Test
    fun `a missing entry has no stamp`() {
        assertNull(ArtifactStamp.of(temporary.resolve("missing.jar")))
    }

    private companion object {
        const val PINNED = 500_000L
    }
}
