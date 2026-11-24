// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.services.ClassFileFixtures.ACC_MODULE
import com.github.albertocavalcante.gvy.gls.services.ClassFileFixtures.ACC_PUBLIC_SUPER
import com.github.albertocavalcante.gvy.gls.services.ClassFileFixtures.ACC_SYNTHETIC
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Random

class ClassFileReaderTest {

    private fun read(bytes: ByteArray, name: String, limit: Long = 1L shl 20): ClassFileRead =
        readClassFile(ClassFileCursor(bytes.inputStream(), ByteArray(64), limit), name.toByteArray())

    private fun accepted(bytes: ByteArray, name: String): ClassAccessFlags =
        (read(bytes, name) as ClassFileRead.Accepted).flags

    @Test
    fun `accepts a minimal class and reports its flags`() {
        val flags = accepted(ClassFileFixtures.minimal("demo/Foo"), "demo/Foo")

        assertThat(flags.isPublic).isTrue()
        assertThat(flags.isIndexable).isTrue()
    }

    @Test
    fun `steps over a constant pool with every tag`() {
        val flags = accepted(ClassFileFixtures.withEveryTag("demo/Foo"), "demo/Foo")

        assertThat(flags.isIndexable).isTrue()
    }

    @Test
    fun `steps over a long constant in the last slots`() {
        val flags =
            accepted(ClassFileFixtures.withEveryTag("demo/Foo", lastWide = ClassFileFixtures.Wide.LONG), "demo/Foo")

        assertThat(flags.isIndexable).isTrue()
    }

    @Test
    fun `steps over a double constant in the last slots`() {
        val bytes = ClassFileFixtures.withEveryTag("demo/Foo", lastWide = ClassFileFixtures.Wide.DOUBLE)

        assertThat(accepted(bytes, "demo/Foo").isIndexable).isTrue()
    }

    @Test
    fun `reads real JDK classes by their own names`() {
        assertThat(
            accepted(ClassFileFixtures.realClass("java/lang/String.class"), "java/lang/String").isPublic,
        ).isTrue()
        assertThat(accepted(ClassFileFixtures.realClass("java/util/Map\$Entry.class"), "java/util/Map\$Entry").isPublic)
            .isTrue()
        assertThat(
            accepted(
                ClassFileFixtures.realClass("java/util/ArrayList\$Itr.class"),
                "java/util/ArrayList\$Itr",
            ).isPublic,
        ).isFalse()
    }

    @Test
    fun `synthetic and module flags make a class not indexable`() {
        val synthetic = accepted(ClassFileFixtures.minimal("demo/Foo", ACC_PUBLIC_SUPER or ACC_SYNTHETIC), "demo/Foo")
        val module = accepted(ClassFileFixtures.minimal("demo/Foo", ACC_PUBLIC_SUPER or ACC_MODULE), "demo/Foo")

        assertThat(synthetic.isSynthetic).isTrue()
        assertThat(synthetic.isIndexable).isFalse()
        assertThat(module.isModule).isTrue()
        assertThat(module.isIndexable).isFalse()
    }

    @Test
    fun `rejects a class whose name is not the expected one`() {
        val result = read(ClassFileFixtures.minimal("demo/Foo"), "BOOT-INF/classes/demo/Foo")

        assertThat(result).isEqualTo(ClassFileRead.Rejected(SkipReason.NAME_MISMATCH))
    }

    @Test
    fun `rejects bytes that are not a class file`() {
        assertThat(
            read("not a class".toByteArray(), "x/Y"),
        ).isEqualTo(ClassFileRead.Rejected(SkipReason.NOT_A_CLASS_FILE))
    }

    @Test
    fun `rejects a constant pool with an unknown tag`() {
        val bytes = ClassFileFixtures.headerWithUtf8(0).also { it[it.size - 3] = 2 } // tag 2 is unassigned

        assertThat(read(bytes, "x/Y")).isEqualTo(ClassFileRead.Rejected(SkipReason.UNKNOWN_CONSTANT_TAG))
    }

    @Test
    fun `ends with an end of stream error at every truncation point`() {
        val bytes = ClassFileFixtures.withEveryTag("demo/Foo", lastWide = ClassFileFixtures.Wide.LONG)

        // Covers magic, version, pool count, every entry's tag and payload, access_flags and this_class.
        for (length in bytes.indices) {
            assertThatThrownBy { read(bytes.copyOf(length), "demo/Foo") }
                .describedAs("truncated to $length of ${bytes.size} bytes")
                .isInstanceOf(EOFException::class.java)
        }
    }

    @Test
    @Timeout(30)
    fun `stops at the byte limit however long the stream claims to be`() {
        val endless = object : InputStream() {
            private val header = ClassFileFixtures.headerWithUtf8(65535)
            private var served = 0

            override fun read(): Int = if (served < header.size) header[served++].toInt() and 0xFF else 'A'.code

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = read().toByte()
                return len
            }
        }

        assertThatThrownBy {
            readClassFile(ClassFileCursor(endless, ByteArray(64), 4096), "x/Y".toByteArray())
        }.isInstanceOf(ClassFileTooLargeException::class.java)
    }

    @Test
    @Timeout(60)
    fun `damaged class files end in a result or an IOException and nothing else`() {
        val originals = listOf(
            ClassFileFixtures.realClass("java/lang/String.class") to "java/lang/String",
            ClassFileFixtures.realClass("java/util/Map\$Entry.class") to "java/util/Map\$Entry",
            ClassFileFixtures.realClass("java/util/ArrayList.class") to "java/util/ArrayList",
            ClassFileFixtures.withEveryTag("demo/Foo", lastWide = ClassFileFixtures.Wide.DOUBLE) to "demo/Foo",
        )
        val random = Random(SEED)
        repeat(CASES) {
            val (original, name) = originals[random.nextInt(originals.size)]
            val damaged = if (random.nextBoolean()) {
                original.copyOf(random.nextInt(original.size + 1))
            } else {
                original.copyOf().also { copy ->
                    repeat(1 + random.nextInt(MAX_FLIPS)) {
                        copy[random.nextInt(copy.size)] = random.nextInt().toByte()
                    }
                }
            }
            try {
                read(damaged, name, limit = LIMIT)
            } catch (_: IOException) {
                // the only failure the reader may report
            }
        }
    }

    private companion object {
        const val SEED = 2186L
        const val CASES = 4000
        const val MAX_FLIPS = 6
        const val LIMIT = 1L shl 16
    }
}
