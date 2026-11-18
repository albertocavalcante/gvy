// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportBlockTest {

    private fun block(text: String): ImportBlock = assertNotNull(ImportBlock.parse(text))

    @Test
    fun `the block starts after the package and header and ends at the last import`() {
        val block = block("#!/bin/groovy\n// header\npackage p\n\nimport a.A\nimport b.B\n\nclass X {}\n")

        assertEquals(4, block.firstLine)
        assertEquals(5, block.lastLine)
        assertEquals(listOf("a.A", "b.B"), block.entries.map { (it.target as ImportTarget.Type).qualifiedName })
    }

    @Test
    fun `comments directly above an import are its own and extend the block start`() {
        val block = block("package p\n\n// a\n/* b */\nimport a.A\n")

        assertEquals(2, block.firstLine)
        assertEquals(listOf("// a", "/* b */"), block.entries.single().leadingComments)
        assertFalse(block.hasUnattributableComment)
    }

    @Test
    fun `a blank line inside a block comment does not end it`() {
        val block = block("/*\n\n still the comment\n*/\nimport a.A\n")

        assertEquals(4, block.entries.single().leadingComments.size)
        assertFalse(block.hasUnattributableComment)
    }

    @Test
    fun `a comment cut off from the next import by a blank line is unattributable`() {
        assertTrue(block("import a.A\n// stray\n\nimport b.B\n").hasUnattributableComment)
        assertFalse(block("import a.A\n\n// mine\nimport b.B\n").hasUnattributableComment)
    }

    @Test
    fun `code before the first import means no block`() {
        assertNull(ImportBlock.parse("println 1\nimport a.A\n"))
        assertNull(ImportBlock.parse("@Library('x') _\nimport a.A\n"))
        assertNull(ImportBlock.parse("package p\n"))
    }

    @Test
    fun `a slash-star-slash is not a complete comment`() {
        // "/*/" opens a comment; the import below is inside it, so it must not become a block entry.
        assertNull(ImportBlock.parse("/*/\nimport a.A\n*/\n"))
    }

    @Test
    fun `code sharing a line with a comment is not part of the block`() {
        assertNull(ImportBlock.parse("/* c */ import a.A\n"))
        assertEquals(0, block("import a.A\n/* c */ import b.B\n").lastLine)
    }
}
