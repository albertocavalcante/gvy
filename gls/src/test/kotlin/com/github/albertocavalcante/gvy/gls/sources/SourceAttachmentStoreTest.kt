// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceAttachmentStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `attachment metadata quota refuses new records and preserves an existing reopen declaration`() = runBlocking {
        val root = directory.resolve("attachments")
        val store = SourceAttachmentStore(root, maximumStoreBytes = 64 * 1024)
        val (symbol, source, archive) = attachment()
        val originalContext = context("one")
        assertTrue(store.record(symbol, originalContext, source, archive, null))
        val revision = store.revision(symbol.origin, originalContext.identity)
        Files.write(root.resolve("full-store"), ByteArray(64 * 1024))
        val failure = assertFailsWith<SourceStoreException> {
            store.record(symbol, context("two"), source, archive, null)
        }
        assertEquals(SourceFailure.LIMIT_EXCEEDED, failure.failure)
        assertEquals(revision, store.revision(symbol.origin, originalContext.identity))
        assertTrue(store.find(symbol, originalContext) is SourceArchiveInput.Verified)
    }

    @Test
    fun `escaped metadata cannot exceed the reservation before a temporary file is created`() = runBlocking {
        val root = directory.resolve("attachments")
        val store = SourceAttachmentStore(root)
        val (symbol, source, archive) = attachment()
        val failure = assertFailsWith<SourceStoreException> {
            store.record(symbol, context("\u0000".repeat(20_000)), source, archive, null)
        }
        assertEquals(SourceFailure.LIMIT_EXCEEDED, failure.failure)
        assertFalse(Files.exists(root))
    }

    private fun attachment(): Triple<ResolvedExternalSymbol, Path, VerifiedSourceArchive> {
        val binary = Files.writeString(directory.resolve("selected.jar"), "selected binary")
        val source = Files.writeString(directory.resolve("chosen-sources.jar"), "source snapshot")
        val archive = SourceArchiveStore(directory.resolve("archives")).importArchive(SourceArchiveInput.Local(source))
        return Triple(ResolvedExternalSymbol("sample.Type", SourceOrigin.Archive(binary)), source, archive)
    }

    private fun context(id: String) = SourceLookupContext(SourceContextIdentity(id), isCurrent = { true })
}
