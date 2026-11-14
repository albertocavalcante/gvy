// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SourceDiskAdmissionTest {
    @TempDir lateinit var root: Path

    @Test
    fun `capacity refusal preserves existing user viewing copies and never enters publication`() {
        val retained = Files.writeString(root.resolve("dirty-view.java"), "local edits")
        var published = false
        val failure = assertFailsWith<SourceStoreException> {
            withSourceDiskAdmission(root, maximumBytes = 16, reservationBytes = 8) { published = true }
        }
        assertEquals(SourceFailure.LIMIT_EXCEEDED, failure.failure)
        assertFalse(published)
        assertEquals("local edits", Files.readString(retained))
    }

    @Test
    fun `path admission reserves the next publication before allowing its files`() {
        repeat(16_368) { Files.createFile(root.resolve("retained-$it")) }
        var published = false
        val failure = assertFailsWith<SourceStoreException> {
            withSourceDiskAdmission(root, maximumBytes = 16, reservationBytes = 8) { published = true }
        }
        assertEquals(SourceFailure.LIMIT_EXCEEDED, failure.failure)
        assertFalse(published)
    }
}
