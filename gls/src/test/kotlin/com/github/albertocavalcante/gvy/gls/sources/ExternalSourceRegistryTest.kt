// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.build.ResolvedArtifactCoordinates
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalSourceRegistryTest {
    @TempDir
    lateinit var temporary: Path

    private fun descriptor(context: String = "one"): SourceDocumentDescriptor {
        val content = "package groovy.transform; public @interface CompileStatic {}"
        val path = temporary.resolve("immutable.java")
        Files.writeString(path, content)
        val digest = ExternalSourceRegistry.sha256(content.toByteArray())
        return SourceDocumentDescriptor(
            sourceDigest = "a".repeat(64),
            contentDigest = digest,
            entryName = "groovy/transform/CompileStatic.java",
            contentPath = path,
            origin = SourceOrigin.Bundled("groovy", URI.create("file:///server.jar")),
            context = SourceContextIdentity(context, URI.create("file:///workspace/Money.groovy"), "fingerprint"),
            displayName = "CompileStatic.java",
            languageId = "java",
        )
    }

    @Test
    fun `identical bytes retain distinct originating contexts`() {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val a = registry.register(descriptor("one"))
        val b = registry.register(descriptor("two"))
        assertNotEquals(a, b)
        assertEquals("gvy-source", a.scheme)
        assertEquals("one", registry.descriptor(a)?.context?.id)
        assertEquals("two", registry.descriptor(b)?.context?.id)
    }

    @Test
    fun `close and restart recover source provenance without retaining active resources`() {
        val root = temporary.resolve("registry")
        val registry = ExternalSourceRegistry(root, virtualDocumentsEnabled = { true })
        val original = descriptor()
        val uri = registry.register(original)
        registry.closed(uri)
        val restarted = ExternalSourceRegistry(root, virtualDocumentsEnabled = { true })
        assertTrue(restarted.isManaged(uri))
        assertEquals(original, restarted.descriptor(uri))
        assertEquals(Files.readString(original.contentPath), restarted.content(uri))
    }

    @Test
    fun `managed source stays browse only when its descriptor disappears`() {
        val root = temporary.resolve("registry")
        val registry = ExternalSourceRegistry(root, virtualDocumentsEnabled = { true })
        val uri = registry.register(descriptor())
        registry.closed(uri)
        Files.list(root.resolve("descriptors")).use { entries -> entries.forEach { Files.delete(it) } }
        val restarted = ExternalSourceRegistry(root)
        assertTrue(restarted.isManaged(uri))
        assertNull(restarted.descriptor(uri))
        assertNull(restarted.content(uri))
        assertFalse(restarted.isManaged(URI.create("file:///workspace/Money.groovy")))
        assertTrue(restarted.isManaged(root.resolve("views/lost/Other.groovy").toUri()))
    }

    @Test
    fun `modified viewing copies are preserved and never trusted for navigation`() {
        val root = temporary.resolve("registry")
        val original = descriptor()
        val registry = ExternalSourceRegistry(root)
        val uri = registry.register(original)
        assertEquals("file", uri.scheme)
        assertNotEquals(original.contentPath, Path.of(uri))
        Files.writeString(Path.of(uri), "user edits")
        assertNull(registry.content(uri))
        assertEquals("user edits", Files.readString(Path.of(uri)))
        assertEquals(original.contentDigest, ExternalSourceRegistry.sha256(Files.readAllBytes(original.contentPath)))
    }

    @Test
    fun `unsaved portable source edits invalidate navigation until close`() {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = registry.register(descriptor())
        registry.opened(uri, "changed buffer")
        assertNull(registry.content(uri))
        registry.closed(uri)
        assertTrue(registry.content(uri)?.contains("CompileStatic") == true)
    }

    @Test
    fun `corrupt immutable content is not returned as verified source`() {
        val original = descriptor()
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = registry.register(original)
        Files.writeString(original.contentPath, "corrupt")
        assertNull(registry.content(uri))
    }

    @Test
    fun `all origin variants survive descriptor serialization`() {
        val original = descriptor()
        val origins = listOf(
            SourceOrigin.Archive(
                temporary.resolve("groovy.jar"),
                ResolvedArtifactCoordinates("org.apache.groovy", "groovy", "4.0.33", "indy", "jar"),
                ArtifactStamp(100, 200, 3),
                "a".repeat(64),
            ),
            SourceOrigin.Archive(temporary.resolve("local.jar")),
            SourceOrigin.Jdk(temporary.resolve("jdk"), "java.base"),
            SourceOrigin.Unknown(URI.create("jrt:/unknown")),
            original.origin,
        )
        val root = temporary.resolve("registry")
        val registry = ExternalSourceRegistry(root, virtualDocumentsEnabled = { true })
        origins.forEach { origin ->
            val value = original.copy(origin = origin)
            val uri = registry.register(value)
            registry.closed(uri)
            assertEquals(value, ExternalSourceRegistry(root).descriptor(uri))
        }
    }

    @Test
    fun `invalid utf8 is unavailable instead of silently changing verified source text`() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        val original = descriptor()
        Files.write(original.contentPath, bytes)
        val invalid = original.copy(contentDigest = ExternalSourceRegistry.sha256(bytes))
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val uri = registry.register(invalid)
        assertNull(registry.content(uri))
    }

    @Test
    fun `context delimiter characters cannot alias separate identities`() {
        val original = descriptor()
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), virtualDocumentsEnabled = { true })
        val first = original.copy(context = SourceContextIdentity("a\u0000b", null, "c"))
        val second = original.copy(context = SourceContextIdentity("a", null, "b\u0000c"))
        assertNotEquals(registry.register(first), registry.register(second))
    }

    @Test
    fun `a file presentation cannot expose immutable source bytes directly`() {
        val original = descriptor()
        val registry = ExternalSourceRegistry(
            temporary.resolve("registry"),
            portablePresentation = SourcePresentation { it.contentPath.toUri() },
            managedRoots = listOf(temporary),
        )
        assertFailsWith<IllegalArgumentException> { registry.register(original) }
        assertEquals(original.contentDigest, ExternalSourceRegistry.sha256(Files.readAllBytes(original.contentPath)))
    }

    @Test
    fun `a custom registry namespace remains managed without metadata and excludes adjacent paths`() {
        val root = temporary.resolve("custom-browser")
        val registry = ExternalSourceRegistry(root, managedRoots = emptyList())
        assertTrue(registry.isManaged(root.resolve("descriptors/missing.properties").toUri()))
        assertTrue(registry.isManaged(root.resolve("views/missing/Source.groovy").toUri()))
        assertFalse(registry.isManaged(temporary.resolve("custom-browser-copy/Source.groovy").toUri()))
    }

    @Test
    fun `document admission does not change namespace classification`() {
        val registry = ExternalSourceRegistry(temporary.resolve("registry"), sourceAdmission = { _, _ -> true })
        val uri = temporary.resolve("alias/Mode.groovy").toUri()
        assertFalse(registry.isManaged(uri))
        assertTrue(registry.shouldGuardDocument(uri))
        assertFalse(registry.isManaged(uri))
    }

    @Test
    fun `metadata admission preserves existing reopen records and avoids warm rewrites when full`() {
        val root = temporary.resolve("registry")
        val registry = ExternalSourceRegistry(
            root,
            virtualDocumentsEnabled = { true },
            maximumMetadataBytes = 128L * 1024,
        )
        val original = descriptor()
        val uri = registry.register(original)
        Files.write(root.resolve("descriptors/full-budget.bin"), ByteArray(128 * 1024))
        assertEquals(uri, registry.register(original), "Existing unchanged mapping needs no new disk admission")
        val failure = assertFailsWith<SourceStoreException> {
            registry.register(original.copy(context = SourceContextIdentity("new-context")))
        }
        assertEquals(SourceFailure.LIMIT_EXCEEDED, failure.failure)
        registry.closed(uri)
        assertEquals(original, ExternalSourceRegistry(root).descriptor(uri))
    }

    @Test
    fun `escaped metadata is bounded before any descriptor file is written`() {
        val root = temporary.resolve("registry")
        val original = descriptor().copy(context = SourceContextIdentity("\u0000".repeat(20_000)))
        val registry = ExternalSourceRegistry(
            root,
            virtualDocumentsEnabled = { true },
            maximumMetadataBytes = 64L * 1024,
        )
        val failure = assertFailsWith<SourceStoreException> { registry.register(original) }
        assertEquals(SourceFailure.LIMIT_EXCEEDED, failure.failure)
        if (Files.exists(root.resolve("descriptors"))) {
            Files.list(root.resolve("descriptors")).use { files ->
                assertEquals(0L, files.filter { it.fileName.toString() != ".source-admission.lock" }.count())
            }
        }
    }
}
