// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals

private const val PUBLIC = Opcodes.ACC_PUBLIC
private const val MAJOR_OFFSET = 6
private const val FIRST_CONSTANT_TAG_OFFSET = 10

private fun sampleClass(): ByteArray = classFile("p/A") {
    interfaces = listOf("java/io/Serializable")
    field(PUBLIC, "count", "I")
    method(PUBLIC, "run", "(ILjava/lang/String;)V") { parameters = listOf("a", "b") }
    method(PUBLIC, "walk", "(I)V") { localVariables = listOf("this", "steps") }
    innerClass("p/A\$Inner", "p/A", "Inner", Opcodes.ACC_STATIC)
    annotation("Ljava/lang/Deprecated;")
}

private fun skipped(bytes: ByteArray, name: String = "p/A"): SkipReason? =
    (extractResult(bytes, name) as? Extraction.Skipped)?.reason

class MalformedClassFileTest {
    @Test
    fun `skips a file without the class magic`() {
        val valid = sampleClass()
        val wrongMagic = valid.copyOf().also { it[0] = 0x00 }
        val lastMagicByte = valid.copyOf().also { it[3] = 0x00 }

        assertEquals(SkipReason.MALFORMED, skipped(wrongMagic))
        assertEquals(SkipReason.MALFORMED, skipped(lastMagicByte))
        assertEquals(SkipReason.MALFORMED, skipped(ByteArray(0)))
        assertEquals(SkipReason.MALFORMED, skipped("not a class file at all".toByteArray()))
        assertEquals(null, skipped(valid))
    }

    @Test
    fun `skips truncated class files at every length`() {
        val valid = sampleClass()

        for (length in 0 until valid.size) {
            assertEquals(SkipReason.MALFORMED, skipped(valid.copyOf(length)), "length $length of ${valid.size}")
        }
    }

    @Test
    fun `skips an unknown constant pool tag as malformed`() {
        val unknownTag = sampleClass().also { it[FIRST_CONSTANT_TAG_OFFSET] = 99 }

        assertEquals(SkipReason.MALFORMED, skipped(unknownTag))
    }

    @Test
    fun `skips class files newer than the ASM in use`() {
        // 0x8000 and above read as negative numbers in ASM, which would let such a file through.
        for (major in listOf(0x7FFF, 0x8000, 0xFFFF)) {
            val tooNew = sampleClass().also {
                it[MAJOR_OFFSET] = (major shr 8).toByte()
                it[MAJOR_OFFSET + 1] = major.toByte()
            }

            assertEquals(SkipReason.UNSUPPORTED_CLASS_VERSION, skipped(tooNew), "major $major")
        }
    }

    @Test
    fun `skips a class whose name does not match its path`() {
        val bytes = sampleClass()

        assertEquals(SkipReason.NAME_MISMATCH, skipped(bytes, name = "p/B"))
        assertEquals(SkipReason.NAME_MISMATCH, skipped(bytes, name = "q/A"))
    }
}
