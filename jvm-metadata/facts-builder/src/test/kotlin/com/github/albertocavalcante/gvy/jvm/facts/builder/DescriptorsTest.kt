// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescriptorsTest {
    @Test
    fun `rejects object types whose name is not a valid internal name`() {
        for (name in listOf("L/;", "La//b;", "La/;", "La[b;", "L/a;", "La.b;", "L;")) {
            assertFalse(Descriptors.isFieldDescriptor(name), name)
            assertEquals(null, Descriptors.parameterCount("($name)V"), "($name)V")
            assertEquals(null, Descriptors.parameterCount("()$name"), "()$name")
        }
        for (name in listOf("La/b/C;", "LC;", "La/B\$1;")) {
            assertTrue(Descriptors.isFieldDescriptor(name), name)
        }
    }

    @Test
    fun `accepts 255 array dimensions and rejects 256`() {
        assertTrue(Descriptors.isFieldDescriptor("[".repeat(255) + "I"))
        assertFalse(Descriptors.isFieldDescriptor("[".repeat(256) + "I"))
        assertTrue(Descriptors.isFieldDescriptor("[".repeat(255) + "La/B;"))
        assertFalse(Descriptors.isFieldDescriptor("[".repeat(256) + "La/B;"))
    }

    @Test
    fun `counts parameters of valid method descriptors`() {
        assertEquals(0, Descriptors.parameterCount("()V"))
        assertEquals(3, Descriptors.parameterCount("(IJ[Ljava/lang/String;)I"))
        assertEquals(null, Descriptors.parameterCount("(I)"))
    }
}
