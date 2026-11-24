// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.source

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTypesTest {
    @Test
    fun `document versions order and compare by the client's number`() {
        assertEquals(DocumentVersion.of(3), DocumentVersion.of(3))
        assertNotEquals(DocumentVersion.of(3), DocumentVersion.of(4))
        assertTrue(DocumentVersion.of(2) < DocumentVersion.of(3))
        assertTrue(DocumentVersion.of(3) <= DocumentVersion.of(3))
        assertTrue(DocumentVersion.of(-1) < DocumentVersion.of(0)) // the protocol allows any Int
        assertEquals(7, DocumentVersion.of(7).value)
        assertEquals("DocumentVersion(7)", DocumentVersion.of(7).toString())
    }

    @Test
    fun `an absent client version stays absent`() {
        assertNull(DocumentVersion.ofOrNull(null))
        assertEquals(DocumentVersion.of(0), DocumentVersion.ofOrNull(0))
    }
}
