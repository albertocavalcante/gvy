// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceContentStoreTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `source files from same named archives and class names remain distinct`(): Unit = runBlocking {
        val first = archive("one/lib-sources.jar", "example/Foo.java" to "package example; class Foo { int one; }")
        val second = archive("two/lib-sources.jar", "example/Foo.java" to "package example; class Foo { int two; }")
        SourceContentStore(temporary.resolve("store")).use { store ->
            val one = store.importArchive(SourceArchiveInput.Local(first))
            val two = store.importArchive(SourceArchiveInput.Local(second))
            val firstSource = assertNotNull(store.sourceFor(one, "example.Foo"))
            val secondSource = assertNotNull(store.sourceFor(two, "example.Foo"))
            assertNotEquals(firstSource.contentPath, secondSource.contentPath)
            assertTrue(Files.readString(firstSource.contentPath).contains("int one"))
            assertTrue(Files.readString(secondSource.contentPath).contains("int two"))
            assertEquals(firstSource, store.sourceFor(one, "example.Foo"))
            assertEquals(SourceStoreWorkCounts(2, 2, 2), store.workCounts())
        }
    }

    @Test
    fun `a single type request materializes only the requested outer source`(): Unit = runBlocking {
        val jar = archive(
            "library.jar",
            "example/Foo.java" to "package example; class Foo { class Inner {} }",
            "example/Other.java" to "package example; class Other {}",
        )
        SourceContentStore(temporary.resolve("store")).use { store ->
            val input = store.importArchive(SourceArchiveInput.Local(jar))
            val source = assertNotNull(store.sourceFor(input, "example.Foo\$Inner"))
            assertEquals("example/Foo.java", source.entryName)
            Files.walk(temporary.resolve("store/content")).use { files ->
                assertEquals(
                    1,
                    files.filter { path ->
                        Files.isRegularFile(path) && path.fileName.toString() != ".source-admission.lock"
                    }.count(),
                )
            }
            assertNull(store.sourceFor(input, "example.Missing"))
        }
    }

    @Test
    fun `repeated and concurrent local inputs share only their freshly observed source digest`(): Unit = runBlocking {
        val jar = archive("local.jar", "example/Foo.java" to "package example; class Foo {}")
        SourceContentStore(temporary.resolve("store")).use { store ->
            val inputs = coroutineScope {
                List(12) { async { store.importArchive(SourceArchiveInput.Local(jar)) } }.awaitAll()
            }
            assertEquals(1, inputs.toSet().size)
            assertEquals(1L, store.workCounts().archiveImports)
            assertEquals(inputs.first(), store.importArchive(SourceArchiveInput.Local(jar)))
            assertEquals(1L, store.workCounts().archiveImports)
            val previousTimestamp = Files.getLastModifiedTime(jar)
            archive("local.jar", "example/Foo.java" to "package example; class Foo { int changed; }")
            Files.setLastModifiedTime(jar, previousTimestamp)
            assertNotEquals(inputs.first(), store.importArchive(SourceArchiveInput.Local(jar)))
            assertEquals(2L, store.workCounts().archiveImports)
        }
    }

    @Test
    fun `a bad expected digest fails before malformed archive bytes are parsed`(): Unit = runBlocking {
        val badArchive = temporary.resolve("malformed.jar")
        Files.writeString(badArchive, "not a zip file")
        SourceContentStore(temporary.resolve("store")).use { store ->
            val failure = assertFailsWith<SourceStoreException> {
                store.importArchive(SourceArchiveInput.Verified(badArchive, "0".repeat(64)))
            }
            assertEquals(SourceFailure.INTEGRITY_MISMATCH, failure.failure)
        }
    }

    @Test
    fun `unsafe and case colliding names invalidate the archive before any source is materialized`(): Unit =
        runBlocking {
            val archives = listOf(
                archive("traversal.jar", "../outside.java" to "x", "example/Foo.java" to "class Foo {}"),
                archive("case.jar", "example/Foo.java" to "class Foo {}", "example/foo.java" to "class foo {}"),
                archive("drive.jar", "C:/outside.java" to "class Outside {}"),
            )
            SourceContentStore(temporary.resolve("store")).use { store ->
                for (jar in archives) {
                    val input = store.importArchive(SourceArchiveInput.Local(jar))
                    assertEquals(
                        SourceFailure.UNSAFE_ARCHIVE,
                        assertFailsWith<SourceStoreException> { store.sourceFor(input, "example.Foo") }.failure,
                    )
                }
                assertTrue(Files.notExists(temporary.resolve("store/content")))
                assertTrue(Files.notExists(temporary.resolve("outside.java")))
            }
        }

    @Test
    fun `expanded entry bytes and source table entries have enforced limits`(): Unit = runBlocking {
        val jar = archive("large.jar", "example/Foo.java" to "x".repeat(128))
        SourceContentStore(temporary.resolve("store"), SourceStoreLimits(maxEntryBytes = 32)).use { store ->
            val input = store.importArchive(SourceArchiveInput.Local(jar))
            assertEquals(
                SourceFailure.LIMIT_EXCEEDED,
                assertFailsWith<SourceStoreException> { store.sourceFor(input, "example.Foo") }.failure,
            )
        }
        val many = archive("many.jar", "example/Foo.java" to "x", "example/Bar.java" to "y")
        SourceContentStore(temporary.resolve("small-index"), SourceStoreLimits(maxArchiveEntries = 1)).use { store ->
            val input = store.importArchive(SourceArchiveInput.Local(many))
            assertEquals(
                SourceFailure.LIMIT_EXCEEDED,
                assertFailsWith<SourceStoreException> { store.sourceFor(input, "example.Foo") }.failure,
            )
        }
    }

    @Test
    fun `concurrent callers receive the same independently verified source entry`(): Unit = runBlocking {
        val jar = archive("library.jar", "example/Foo.java" to "package example; class Foo {}")
        SourceContentStore(temporary.resolve("store")).use { store ->
            val input = store.importArchive(SourceArchiveInput.Local(jar))
            val sources = coroutineScope { List(12) { async { store.sourceFor(input, "example.Foo") } }.awaitAll() }
            assertNotNull(sources.first())
            assertEquals(1, sources.toSet().size)
            assertEquals(SourceStoreWorkCounts(1, 1, 1), store.workCounts())
            Files.walk(temporary.resolve("store/content")).use { files ->
                assertEquals(
                    1,
                    files.filter { path ->
                        Files.isRegularFile(path) && path.fileName.toString() != ".source-admission.lock"
                    }.count(),
                )
            }
        }
    }

    @Test
    fun `modified cached content is rejected without overwriting the changed file`(): Unit = runBlocking {
        val jar = archive("library.jar", "example/Foo.java" to "package example; class Foo {}")
        SourceContentStore(temporary.resolve("store")).use { store ->
            val input = store.importArchive(SourceArchiveInput.Local(jar))
            val source = assertNotNull(store.sourceFor(input, "example.Foo"))
            Files.writeString(source.contentPath, "user modified source")
            assertEquals(
                SourceFailure.INTEGRITY_MISMATCH,
                assertFailsWith<SourceStoreException> { store.sourceFor(input, "example.Foo") }.failure,
            )
            assertEquals("user modified source", Files.readString(source.contentPath))
        }
    }

    @Test
    fun `central directory allocation is bounded before ZipFile parses a forged table`(): Unit = runBlocking {
        val jar = archive("forged.jar", "example/Foo.java" to "class Foo {}")
        val bytes = Files.readAllBytes(jar)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 22 + 12, 0x70000000)
        Files.write(jar, bytes)
        SourceContentStore(temporary.resolve("store")).use { store ->
            val imported = store.importArchive(SourceArchiveInput.Local(jar))
            assertEquals(
                SourceFailure.LIMIT_EXCEEDED,
                assertFailsWith<SourceStoreException> { store.sourceFor(imported, "example.Foo") }.failure,
            )
        }
    }

    @Test
    fun `archive disk admission reuses verified content after restart and never deletes it`(): Unit = runBlocking {
        val jar = archive("small.jar", "example/Foo.java" to "class Foo {}")
        val root = temporary.resolve("store")
        val limits = SourceStoreLimits(maxArchiveBytes = 1024, maxArchiveStoreBytes = 1024)
        val imported = SourceContentStore(root, limits).use { it.importArchive(SourceArchiveInput.Local(jar)) }
        SourceContentStore(root, limits).use { restarted ->
            assertEquals(imported, restarted.importArchive(SourceArchiveInput.Local(jar)))
            assertTrue(Files.isRegularFile(imported.path))
            verifyFileDigest(imported.path, imported.sha256, 1024)
        }
    }

    @Test
    fun `materialized content is reused after restart without another disk reservation`(): Unit = runBlocking {
        val jar = archive("small.jar", "example/Foo.java" to "class Foo {}")
        val root = temporary.resolve("store")
        val limits = SourceStoreLimits(
            maxArchiveBytes = 1024,
            maxEntryBytes = 512,
            maxArchiveStoreBytes = 1024,
            maxContentStoreBytes = 512,
        )
        val original = SourceContentStore(root, limits).use { store ->
            val archive = store.importArchive(SourceArchiveInput.Local(jar))
            assertNotNull(store.sourceFor(archive, "example.Foo"))
        }

        SourceContentStore(root, limits).use { restarted ->
            val archive = restarted.importArchive(SourceArchiveInput.Local(jar))
            val reused = assertNotNull(restarted.sourceFor(archive, "example.Foo"))
            assertEquals(original, reused)
            assertEquals("class Foo {}", Files.readString(reused.contentPath))
        }
    }

    @Test
    fun `corrupted archive cache is rejected during restart reuse`(): Unit = runBlocking {
        val jar = archive("small.jar", "example/Foo.java" to "class Foo {}")
        val root = temporary.resolve("store")
        val limits = SourceStoreLimits(maxArchiveBytes = 1024, maxArchiveStoreBytes = 1024)
        val imported = SourceContentStore(root, limits).use { it.importArchive(SourceArchiveInput.Local(jar)) }
        Files.writeString(imported.path, "corrupt cache bytes")

        SourceContentStore(root, limits).use { restarted ->
            val failure = assertFailsWith<SourceStoreException> {
                restarted.importArchive(SourceArchiveInput.Local(jar))
            }
            assertEquals(SourceFailure.INTEGRITY_MISMATCH, failure.failure)
            assertEquals("corrupt cache bytes", Files.readString(imported.path))
        }
    }

    @Test
    fun `corrupted materialized content is rejected during restart reuse and preserved`(): Unit = runBlocking {
        val jar = archive("small.jar", "example/Foo.java" to "class Foo {}")
        val root = temporary.resolve("store")
        val limits = SourceStoreLimits(
            maxArchiveBytes = 1024,
            maxEntryBytes = 512,
            maxArchiveStoreBytes = 1024,
            maxContentStoreBytes = 512,
        )
        val source = SourceContentStore(root, limits).use { store ->
            val archive = store.importArchive(SourceArchiveInput.Local(jar))
            assertNotNull(store.sourceFor(archive, "example.Foo"))
        }
        Files.writeString(source.contentPath, "user modified source")

        SourceContentStore(root, limits).use { restarted ->
            val archive = restarted.importArchive(SourceArchiveInput.Local(jar))
            val failure = assertFailsWith<SourceStoreException> {
                restarted.sourceFor(archive, "example.Foo")
            }
            assertEquals(SourceFailure.INTEGRITY_MISMATCH, failure.failure)
            assertEquals("user modified source", Files.readString(source.contentPath))
        }
    }

    private fun archive(name: String, vararg entries: Pair<String, String>): Path {
        val path = temporary.resolve(name)
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return path
    }
}
