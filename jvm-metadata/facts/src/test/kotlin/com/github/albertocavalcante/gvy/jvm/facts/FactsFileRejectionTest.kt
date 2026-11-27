// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FactsFileRejectionTest {
    private val storedFile = writeWith(sampleFacts(), SectionEncoding.NONE)
    private val deflatedFile = writeWith(sampleFacts(), SectionEncoding.deflate(6))

    @Test
    fun `a file that does not start with the magic is rejected as bad magic`() {
        FactsFile.open(storedFile.withByte(0, 'X'.code)).assertRejected(RejectReason.BAD_MAGIC)
        FactsFile.open(storedFile.withByte(3, 'X'.code)).assertRejected(RejectReason.BAD_MAGIC)
        FactsFile.open("not a facts file at all".toByteArray()).assertRejected(RejectReason.BAD_MAGIC)
        FactsFile.open("GX".toByteArray()).assertRejected(RejectReason.BAD_MAGIC)
    }

    @Test
    fun `an empty file and a prefix of the magic are truncated`() {
        for (length in 0..4) {
            FactsFile.open(storedFile.copyOf(length)).assertRejected(RejectReason.TRUNCATED)
        }
    }

    @Test
    fun `no proper prefix of a valid file loads or throws`() {
        for (file in listOf(storedFile, deflatedFile)) {
            for (length in 0 until file.size) {
                FactsFile.open(file.copyOf(length)).assertRejected(RejectReason.TRUNCATED)
            }
            FactsFile.open(file).loaded()
        }
    }

    @Test
    fun `bytes after the last section are corrupt`() {
        FactsFile.open(storedFile + byteArrayOf(0)).assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a file above the file cap is oversized and a file at the cap loads`() {
        val tooSmall = ReadLimits(maxFileBytes = storedFile.size - 1)

        FactsFile.open(storedFile, tooSmall).assertRejected(RejectReason.OVERSIZED)
        FactsFile.open(storedFile, ReadLimits(maxFileBytes = storedFile.size)).loaded()
    }

    @Test
    fun `limits must be positive`() {
        assertFailsWith<IllegalArgumentException> { ReadLimits(maxFileBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ReadLimits(maxSectionBytes = 0) }
    }

    @Test
    fun `a flipped byte in any stored section is corrupt`() {
        for (location in sectionLocations(storedFile)) {
            for (index in location.offset until location.offset + location.stored) {
                val file = openFile(storedFile.withFlippedByte(index))
                file.loadAll().assertRejected(RejectReason.CORRUPT)
            }
        }
    }

    @Test
    fun `a flipped byte inside a compressed section is corrupt`() {
        for (location in sectionLocations(deflatedFile)) {
            val file = openFile(deflatedFile.withFlippedByte(location.offset + location.stored / 2))
            file.loadAll().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `a damaged types section does not stop names or extensions from loading`() {
        val types = sectionLocations(storedFile).single { it.id == TYPES }
        val file = openFile(storedFile.withFlippedByte(types.offset))

        assertEquals(sampleFacts().names, file.names().loaded())
        assertEquals(sampleFacts().extensions, file.extensions().loaded())
        file.types().assertRejected(RejectReason.CORRUPT)
        file.loadAll().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `names load while a compressed types section is garbage and types is rejected when requested`() {
        val types = sectionLocations(deflatedFile).single { it.id == TYPES }
        val damaged = deflatedFile.copyOf().also { it.fill(0x5A, types.offset, types.offset + types.stored) }
        val file = openFile(damaged)

        assertEquals(sampleFacts().names, file.names().loaded())
        file.types().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a declared raw length above the cap is oversized`() {
        val lying = TestSection(NAMES, STORED, byteArrayOf(0, 0), rawLength = 200_000_000, crc = 0)

        openFile(buildFile(withSection(lying))).names().assertRejected(RejectReason.OVERSIZED)
        openFile(storedFile, ReadLimits(maxSectionBytes = 8)).names().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `a section whose raw length equals the cap loads`() {
        val largest = sectionLocations(storedFile).maxOf { it.raw }

        openFile(storedFile, ReadLimits(maxSectionBytes = largest)).loadAll().loaded()
        val tooSmall = ReadLimits(maxSectionBytes = largest - 1)

        openFile(storedFile, tooSmall).loadAll().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `a table length of two gibibytes or more is oversized`() {
        val row = sectionLocations(storedFile).first()
        val huge = byteArrayOf(0, 0, 0, 0x80.toByte())

        for (field in listOf(2, 6)) {
            var patched = storedFile
            for (index in huge.indices) {
                patched = patched.withByte(row.entryOffset + field + index, huge[index].toInt())
            }
            FactsFile.open(patched).assertRejected(RejectReason.OVERSIZED)
        }
    }

    @Test
    fun `a stream that inflates beyond its declared length is corrupt`() {
        val section = deflated(NAMES, ByteArray(100) { 1 }, declaredRawLength = 50)

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a stream shorter than its declared length is corrupt`() {
        val section = deflated(NAMES, ByteArray(10) { 1 }, declaredRawLength = 50)

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a stream followed by unread input is corrupt`() {
        val raw = ByteArray(20) { 1 }
        val section = TestSection(NAMES, ZLIB, zlib(raw) + byteArrayOf(0), raw.size, crcOf(raw))

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a stream that ends before its data is complete is corrupt`() {
        val raw = ByteArray(200) { index -> (index * 7).toByte() }
        val whole = zlib(raw)
        val section = TestSection(NAMES, ZLIB, whole.copyOf(whole.size / 2), raw.size, crcOf(raw))

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a compressed section above the inflate ratio is oversized before it is inflated`() {
        val section = deflated(NAMES, ByteArray(100_000))
        assertTrue(section.rawLength > FactsFormat.MAX_INFLATE_RATIO * section.stored.size)

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `the inflate ratio limit is inclusive`() {
        val atLimit = TestSection(NAMES, ZLIB, ByteArray(10), rawLength = 500, crc = 0)
        val above = TestSection(NAMES, ZLIB, ByteArray(10), rawLength = 501, crc = 0)

        // Neither stream is valid: at the limit it is read and found corrupt, above it the length is refused.
        openFile(buildFile(withSection(atLimit))).names().assertRejected(RejectReason.CORRUPT)
        openFile(buildFile(withSection(above))).names().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `a stream that asks for a preset dictionary is corrupt`() {
        // A zlib header with the FDICT flag, a dictionary id, a final empty fixed-Huffman block and the Adler-32 of
        // empty output: valid except for the dictionary, which the reader cannot supply.
        val stream = byteArrayOf(0x78, 0xBB.toByte(), 0, 0, 0, 1, 0x03, 0x00, 0, 0, 0, 1)
        val section = TestSection(NAMES, ZLIB, stream, rawLength = 0, crc = 0)

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a stored section whose stored and raw lengths differ is corrupt`() {
        val section = TestSection(NAMES, STORED, ByteArray(3), rawLength = 4, crc = 0)

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `an unknown codec on a known section is corrupt`() {
        val section = TestSection(NAMES, codec = 9, stored = ByteArray(2), rawLength = 2, crc = 0)

        openFile(buildFile(withSection(section))).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a missing section is corrupt only for what needs it`() {
        val withoutTypes = openFile(buildFile(sections().filter { it.id != TYPES }))
        val withoutStrings = openFile(buildFile(sections().filter { it.id != STRINGS }))

        withoutTypes.names().loaded()
        withoutTypes.types().assertRejected(RejectReason.CORRUPT)
        withoutTypes.loadAll().assertRejected(RejectReason.CORRUPT)
        withoutStrings.names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a section id that appears twice is corrupt`() {
        FactsFile.open(buildFile(sections() + plain(NAMES, ByteArray(2)))).assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `an unknown input kind or a negative input length is corrupt`() {
        FactsFile.open(buildFile(sections(), inputKind = 9)).assertRejected(RejectReason.CORRUPT)
        FactsFile.open(buildFile(sections(), inputLength = -1L)).assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a header string above the limit is oversized`() {
        val tooLong = FactsFormat.MAX_HEADER_STRING_BYTES + 1

        FactsFile.open(header { varint(tooLong) }).assertRejected(RejectReason.OVERSIZED)
        FactsFile.open(header { utf8("p").varint(tooLong + 1) }).assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `a header string that is not valid UTF-8 is corrupt`() {
        val invalid = byteArrayOf(0xC0.toByte(), 0x80.toByte())

        FactsFile.open(header { varint(invalid.size).raw(invalid) }).assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a table that claims more sections than the limit is oversized`() {
        FactsFile.open(header { utf8("p").varint(0).varint(65) }).assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `a table that claims more sections than the file holds is truncated without reading past the end`() {
        FactsFile.open(header { utf8("p").varint(0).varint(64) }).assertRejected(RejectReason.TRUNCATED)
    }

    @Test
    fun `stored lengths that add up beyond the file are truncated even when their sum overflows an Int`() {
        val largest = Int.MAX_VALUE.toLong()
        val file = header {
            utf8("p").varint(0).varint(2)
            u8(STRINGS).u8(STORED).le(largest, 4).le(largest, 4).le(0, 4)
            u8(NAMES).u8(STORED).le(largest, 4).le(largest, 4).le(0, 4)
        }

        FactsFile.open(file).assertRejected(RejectReason.TRUNCATED)
    }

    @Test
    fun `the input SHA-256 must match when the caller expects one`() {
        FactsFile.open(storedFile, expectedInputSha256 = sampleHeader().inputSha256).loaded()
        val other = sampleSha(seed = 2)

        FactsFile.open(storedFile, expectedInputSha256 = other).assertRejected(RejectReason.INPUT_MISMATCH)
        FactsFile.open(storedFile, expectedInputSha256 = null).loaded()
    }

    @Test
    fun `a file that is both truncated and for another input is reported as truncated`() {
        val prefix = storedFile.copyOf(storedFile.size - 1)
        val other = sampleSha(seed = 2)

        FactsFile.open(prefix, expectedInputSha256 = other).assertRejected(RejectReason.TRUNCATED)
    }

    @Test
    fun `random damage to a valid file is always a typed result and never an exception`() {
        val random = Random(20_261_126)
        val limits = ReadLimits(maxFileBytes = 1 shl 20, maxSectionBytes = 1 shl 20)
        var rejected = 0

        repeat(3_000) { iteration ->
            val original = if (iteration % 2 == 0) storedFile else deflatedFile
            val damaged = original.copyOf(if (random.nextInt(4) == 0) random.nextInt(original.size) else original.size)
            repeat(1 + random.nextInt(4)) {
                if (damaged.isNotEmpty()) damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte()
            }
            if (allResults(FactsFile.open(damaged, limits)).any { it is LoadResult.Rejected }) rejected++
        }

        assertTrue(rejected > 1_000, "Expected most damaged files to be rejected, got $rejected")
    }

    /** The result of opening, and when it opened, of every way to decode the file. */
    private fun allResults(opened: LoadResult<FactsFile>): List<LoadResult<*>> {
        if (opened !is LoadResult.Loaded) return listOf(opened)
        val file = opened.value
        return listOf(opened, file.names(), file.types(), file.extensions(), file.loadAll())
    }

    private fun withSection(replacement: TestSection): List<TestSection> =
        sections().map { if (it.id == replacement.id) replacement else it }

    /** A header up to its first free field: magic, version, kind, length and digest, then what [rest] appends. */
    private fun header(rest: TestBytes.() -> Unit): ByteArray = bytes {
        raw("GVYF".toByteArray(Charsets.US_ASCII))
        le(1, 2)
        le(0, 2)
        u8(1)
        le(0, 8)
        raw(sampleSha().toByteArray())
        rest()
    }
}
