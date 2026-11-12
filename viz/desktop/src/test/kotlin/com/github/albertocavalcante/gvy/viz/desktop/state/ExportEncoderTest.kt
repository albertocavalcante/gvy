// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportEncoderTest {
    @Test
    fun `JSON encodes the captured DTO without reparsing current source`() {
        val tree = CoreAstNodeDto("captured", "test", null, emptyList(), mapOf("operator" to "+"))
        val encoded = encodeExport(ExportSnapshot(ExportFormat.JSON, "unrelated source", tree))
        assertEquals(tree, Json.decodeFromString<AstNodeDto>(encoded))
    }

    @Test
    fun `DOT encodes actual captured Core source without requiring a DTO`() {
        val encoded = encodeExport(ExportSnapshot(ExportFormat.DOT, "class Captured {}", null))
        assertTrue(encoded.startsWith("digraph AST"))
        assertTrue(encoded.contains("Captured"))
    }

    @Test
    fun `JSON without a captured tree fails before writing`() {
        assertFailsWith<IllegalArgumentException> { encodeExport(ExportSnapshot(ExportFormat.JSON, "", null)) }
    }
}
