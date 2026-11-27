// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.nio.charset.CharsetDecoder

private const val SECTION_ENTRY_BYTES = 14

/** One row of the section table, with the position of the stored bytes in the file. */
internal class SectionEntry(
    val codec: Int,
    val offset: Int,
    val storedLength: Int,
    val rawLength: Int,
    val rawCrc32c: Int,
)

internal class ParsedFile(
    val header: FactsHeader,
    val formatMinor: Int,
    val headerLength: Int,
    val sections: Map<Int, SectionEntry>,
)

/** Reads and checks the header and the section table; throws [Rejection] for anything malformed. */
internal fun parseFile(bytes: ByteArray, limits: ReadLimits, expectedInput: Sha256?): ParsedFile {
    requireThat(bytes.size <= limits.maxFileBytes, RejectReason.OVERSIZED)
    checkMagic(bytes)
    val cursor = ByteCursor(bytes, FactsFormat.MAGIC.size, bytes.size, RejectReason.TRUNCATED)
    val major = cursor.readLe(Short.SIZE_BYTES).toInt()
    val minor = cursor.readLe(Short.SIZE_BYTES).toInt()
    requireThat(major == FactsFormat.MAJOR, RejectReason.UNSUPPORTED_VERSION)
    val header = readHeader(cursor, bytes)
    val rows = readSectionRows(cursor)
    val headerLength = cursor.position
    val sections = locateSections(rows, headerLength, cursor.remaining)
    if (expectedInput != null) requireThat(header.inputSha256 == expectedInput, RejectReason.INPUT_MISMATCH)
    return ParsedFile(header, minor, headerLength, sections)
}

/** A file that is a proper prefix of the magic is truncated; any other difference is a bad magic. */
private fun checkMagic(bytes: ByteArray) {
    val magic = FactsFormat.MAGIC
    for (index in 0 until minOf(bytes.size, magic.size)) {
        requireThat(bytes[index] == magic[index], RejectReason.BAD_MAGIC)
    }
    requireThat(bytes.size >= magic.size, RejectReason.TRUNCATED)
}

private fun readHeader(cursor: ByteCursor, bytes: ByteArray): FactsHeader {
    val inputKind = InputKind.entries.decodeEnum(cursor.readByte()) { it.code }
    val inputLength = cursor.readLe(Long.SIZE_BYTES)
    requireThat(inputLength >= 0, RejectReason.CORRUPT)
    val inputSha256 = Sha256.of(cursor.readBytes(Sha256.SIZE_BYTES))
    val decoder = newStrictUtf8Decoder()
    val producerVersion = readHeaderString(cursor, bytes, decoder, cursor.readVarint())
    val coordinateField = cursor.readVarint()
    val coordinate = if (coordinateField == 0) null else readHeaderString(cursor, bytes, decoder, coordinateField - 1)
    return FactsHeader(producerVersion, inputKind, coordinate, inputSha256, inputLength)
}

private fun readHeaderString(cursor: ByteCursor, bytes: ByteArray, decoder: CharsetDecoder, length: Int): String {
    requireThat(length <= FactsFormat.MAX_HEADER_STRING_BYTES, RejectReason.OVERSIZED)
    val start = cursor.skip(length)
    return decodeUtf8Strict(decoder, bytes, start, length)
}

private class SectionRow(val id: Int, val codec: Int, val storedLength: Int, val rawLength: Int, val crc: Int)

private fun readSectionRows(cursor: ByteCursor): List<SectionRow> {
    val count = cursor.readVarint()
    requireThat(count <= FactsFormat.MAX_SECTIONS, RejectReason.OVERSIZED)
    cursor.requireCount(count, SECTION_ENTRY_BYTES)
    return List(count) {
        SectionRow(
            id = cursor.readByte(),
            codec = cursor.readByte(),
            storedLength = readLength(cursor),
            rawLength = readLength(cursor),
            crc = cursor.readLe(Int.SIZE_BYTES).toInt(),
        )
    }
}

/** Reads an unsigned 32-bit length; a value that no array could hold is oversized. */
private fun readLength(cursor: ByteCursor): Int {
    val length = cursor.readLe(Int.SIZE_BYTES)
    requireThat(length <= Int.MAX_VALUE.toLong(), RejectReason.OVERSIZED)
    return length.toInt()
}

/** Places each section after the table, in table order, and requires them to fill the rest of the file. */
private fun locateSections(rows: List<SectionRow>, dataStart: Int, dataBytes: Int): Map<Int, SectionEntry> {
    val total = rows.sumOf { row -> row.storedLength.toLong() }
    requireThat(total <= dataBytes, RejectReason.TRUNCATED)
    requireThat(total == dataBytes.toLong(), RejectReason.CORRUPT)
    val sections = HashMap<Int, SectionEntry>()
    var offset = dataStart
    for (row in rows) {
        requireThat(row.id !in sections, RejectReason.CORRUPT)
        sections[row.id] = SectionEntry(row.codec, offset, row.storedLength, row.rawLength, row.crc)
        offset += row.storedLength
    }
    return sections
}
