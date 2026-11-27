// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PUBLIC = Opcodes.ACC_PUBLIC

class ParameterNamesTest {
    @Test
    fun `parameter names come from MethodParameters`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "run", "(ILjava/lang/String;)V") { parameters = listOf("count", "label") }
        }

        assertEquals(listOf("count", "label"), extractRead(bytes, "p/A").member("run").parameterNames)
    }

    @Test
    fun `parameter names are absent without MethodParameters even when LocalVariableTable has names`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "run", "(II)V") { localVariables = listOf("this", "left", "right") }
        }

        // The fixture really carries the table, so a reader that used it would find names.
        assertTrue(String(bytes, StandardCharsets.ISO_8859_1).contains("LocalVariableTable"))
        assertNull(extractRead(bytes, "p/A").member("run").parameterNames)
    }

    @Test
    fun `a method without parameters has an empty list and a field has none`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "run", "()V")
            method(PUBLIC, "walk", "(I)V")
            field(PUBLIC, "count", "I")
        }

        val read = extractRead(bytes, "p/A")

        // ASM never reports an empty MethodParameters attribute, so a method without parameters reads the same
        // with or without one.
        assertEquals(emptyList<String>(), read.member("run").parameterNames)
        assertNull(read.member("walk").parameterNames)
        assertNull(read.member("count").parameterNames)
    }

    @Test
    fun `an unnamed MethodParameters entry is stored as an empty name`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "run", "(II)V") { parameters = listOf("first", null) }
        }

        assertEquals(listOf("first", ""), extractRead(bytes, "p/A").member("run").parameterNames)
    }

    @Test
    fun `parameter names are absent when MethodParameters count differs from the descriptor`() {
        val bytes = classFile("p/A") {
            method(PUBLIC, "tooFew", "(II)V") { parameters = listOf("only") }
            method(PUBLIC, "tooMany", "(II)V") { parameters = listOf("a", "b", "c") }
            method(PUBLIC, "unexpected", "()V") { parameters = listOf("a") }
        }

        val read = extractRead(bytes, "p/A")

        assertNull(read.member("tooFew").parameterNames)
        assertNull(read.member("tooMany").parameterNames)
        assertNull(read.member("unexpected").parameterNames)
    }
}
