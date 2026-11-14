// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

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
