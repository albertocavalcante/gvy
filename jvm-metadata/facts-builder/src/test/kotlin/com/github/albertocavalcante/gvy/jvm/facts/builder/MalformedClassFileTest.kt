// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals

private const val PUBLIC = Opcodes.ACC_PUBLIC
private const val MAGIC = -889275714
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

    @Test
    fun `skips a class whose annotation values nest too deeply to read`() {
        // ASM skips an array value recursively, so a deep one overflows the stack.
        val inClass = classFile("p/A") { rawAttribute("RuntimeVisibleAnnotations", nestedAnnotationAttribute(DEPTH)) }
        val inMethod = classFile("p/A") {
            method(PUBLIC, "run", "()V") {
                rawAttributes.add(RawAttribute("RuntimeVisibleAnnotations", nestedAnnotationAttribute(DEPTH)))
            }
        }

        assertEquals(SkipReason.MALFORMED, skipped(inClass), "class annotation")
        assertEquals(SkipReason.MALFORMED, skipped(inMethod), "method annotation")
    }

    @Test
    fun `an attribute longer than the file is malformed and allocates nothing large`() {
        // Older ASM versions allocate the declared length before checking it against the buffer.
        assertEquals(SkipReason.MALFORMED, skipped(classWithOversizedAttribute()))
    }
}

private const val DEPTH = 200_000

/** A minimal valid class `p/A` with one unknown class attribute that declares 2 GiB of content and has none. */
private fun classWithOversizedAttribute(): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeInt(MAGIC)
        out.writeShort(0)
        out.writeShort(Opcodes.V17)
        out.writeShort(6) // constant pool count
        out.writeByte(1) // 1: Utf8 p/A
        out.writeUTF("p/A")
        out.writeByte(7) // 2: Class 1
        out.writeShort(1)
        out.writeByte(1) // 3: Utf8 java/lang/Object
        out.writeUTF("java/lang/Object")
        out.writeByte(7) // 4: Class 3
        out.writeShort(3)
        out.writeByte(1) // 5: Utf8 Weird
        out.writeUTF("Weird")
        out.writeShort(PUBLIC_SUPER)
        out.writeShort(2) // this_class
        out.writeShort(4) // super_class
        out.writeShort(0) // interfaces
        out.writeShort(0) // fields
        out.writeShort(0) // methods
        out.writeShort(1) // attributes
        out.writeShort(5) // attribute name: Weird
        out.writeInt(Int.MAX_VALUE - 15) // attribute length, with no content after it
    }
    return bytes.toByteArray()
}
