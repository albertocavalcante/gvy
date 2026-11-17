// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IdentifierRangesAnnotationArgumentsTest {
    private fun find(text: String, name: String): Range? {
        val index = TextIndex(text)
        return firstNameOccurrence(index, Range(Position(0, 0), index.positionOf(text.length)), name)
    }

    private fun rangeOf(text: String, name: String, after: String): Range {
        val start = text.indexOf(name, text.indexOf(after))
        return Range(Position(0, start), Position(0, start + name.length))
    }

    @Test
    fun `an annotation argument is not mistaken for the declared name`() {
        val text = "@SuppressWarnings(value = 'unused') def value() { 1 }"
        assertEquals(rangeOf(text, "value", "def"), find(text, "value"))
    }

    @Test
    fun `nested parentheses and strings inside annotation arguments are skipped`() {
        val text = "@Ann(value = foo(')', value), other = \"(\") def value() { 1 }"
        assertEquals(rangeOf(text, "value", "def"), find(text, "value"))
    }

    @Test
    fun `a name that occurs only in annotation arguments is still found`() {
        val text = "@Ann(value)"
        assertEquals(rangeOf(text, "value", "("), find(text, "value"))
    }

    @Test
    fun `the annotation's own name is still found`() {
        val text = "@Ann(value = 1) def x"
        assertEquals(rangeOf(text, "Ann", "@"), find(text, "Ann"))
    }
}
