// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PortableSourcePresentationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `editing a portable view never changes verified source content or erases the edited copy`() {
        val descriptor = descriptor("first")
        val presentation = PortableSourcePresentation(temporary.resolve("views"))
        val first = Path.of(presentation.register(descriptor))
        assertNotEquals(descriptor.contentPath, first)
        Files.writeString(first, "my edits")
        val second = Path.of(presentation.register(descriptor))
        assertNotEquals(first, second)
        assertEquals("my edits", Files.readString(first))
        assertEquals("package example; class Foo {}", Files.readString(second))
        assertEquals("package example; class Foo {}", Files.readString(descriptor.contentPath))
    }

    @Test
    fun `identical source used by two targets has separate presentation identities`() {
        val descriptor = descriptor("first")
        val presentation = PortableSourcePresentation(temporary.resolve("views"))
        val first = presentation.register(descriptor)
        assertEquals(first, presentation.register(descriptor))
        val second = presentation.register(descriptor.copy(context = SourceContextIdentity("second")))
        assertNotEquals(first, second)
    }

    @Test
    fun `equal basenames from different archive entries never share a view`() {
        val descriptor = descriptor("first")
        val presentation = PortableSourcePresentation(temporary.resolve("views"))
        val firstDescriptor = descriptor.copy(entryName = "a/Foo.java")
        val secondDescriptor = descriptor.copy(entryName = "b/Foo.java")

        val first = presentation.register(firstDescriptor)
        val second = presentation.register(secondDescriptor)
        assertNotEquals(first, second)
        assertEquals(first, PortableSourcePresentation(temporary.resolve("views")).register(firstDescriptor))
        assertEquals(second, PortableSourcePresentation(temporary.resolve("views")).register(secondDescriptor))
    }

    @Test
    fun `unchanged view is reused after restart even when the disk budget is full`() {
        val root = temporary.resolve("views")
        val descriptor = descriptor("first")
        val first = PortableSourcePresentation(root, maxViewBytes = 8L * 1024 * 1024).register(descriptor)
        Files.write(root.resolve("full-budget.bin"), ByteArray(8 * 1024 * 1024))

        val restarted = PortableSourcePresentation(root, maxViewBytes = 8L * 1024 * 1024)
        assertEquals(first, restarted.register(descriptor))
    }

    @Test
    fun `cached views fail closed when their parent is replaced by a symlink`() {
        assumeTrue(
            !System.getProperty("os.name").startsWith("Windows"),
            "Symlink privileges are not assumed on Windows",
        )
        val descriptor = descriptor("first")
        val root = temporary.resolve("views")
        val presentation = PortableSourcePresentation(root)
        val viewFile = Path.of(presentation.register(descriptor))
        val viewDirectory = viewFile.parent
        val external = temporary.resolve("external").also { Files.createDirectories(it) }
        Files.writeString(external.resolve(viewFile.fileName), Files.readString(viewFile))
        Files.delete(viewFile)
        Files.delete(viewDirectory)
        Files.createSymbolicLink(viewDirectory, external)

        val failure = assertFailsWith<SourceStoreException> { presentation.register(descriptor) }
        assertEquals(SourceFailure.UNSAFE_ARCHIVE, failure.failure)
    }

    @Test
    fun `view quota protects existing edited copies instead of evicting them`() {
        val descriptor = descriptor("first")
        val presentation = PortableSourcePresentation(temporary.resolve("views"), maxViewBytes = 8L * 1024 * 1024)
        val first = Path.of(presentation.register(descriptor))
        Files.writeString(first, "preserve my edits")
        assertEquals(
            SourceFailure.LIMIT_EXCEEDED,
            assertFailsWith<SourceStoreException> { presentation.register(descriptor) }.failure,
        )
        assertEquals("preserve my edits", Files.readString(first))
    }

    private fun descriptor(context: String): SourceDocumentDescriptor {
        val text = "package example; class Foo {}"
        val file = temporary.resolve("immutable/Foo.java")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return SourceDocumentDescriptor(
            sourceDigest = "a".repeat(64),
            contentDigest = sourceDigest(text),
            entryName = "example/Foo.java",
            contentPath = file.toAbsolutePath(),
            origin = SourceOrigin.Unknown(URI.create("jar:file:///library.jar!/example/Foo.class")),
            context = SourceContextIdentity(context),
            displayName = "example:library:1",
            languageId = "java",
        )
    }
}
