// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassVersionTest {
    @Test
    fun `the newest readable version is at least Java 21`() {
        assertTrue(NewestReadableMajor.value >= Opcodes.V21, "newest readable major ${NewestReadableMajor.value}")
    }

    @Test
    fun `reads a class at Java 21 and at the newest version the probe reports`() {
        for (major in listOf(Opcodes.V21, NewestReadableMajor.value)) {
            val bytes = classFile("p/A") { version = major }

            assertEquals(null, (extractResult(bytes, "p/A") as? Extraction.Skipped)?.reason, "major $major")
        }
    }

    @Test
    fun `skips a class one version newer than the newest readable one as unsupported`() {
        val bytes = classFile("p/A") { version = NewestReadableMajor.value + 1 }

        assertEquals(Extraction.Skipped(SkipReason.UNSUPPORTED_CLASS_VERSION), extractResult(bytes, "p/A"))
    }
}
