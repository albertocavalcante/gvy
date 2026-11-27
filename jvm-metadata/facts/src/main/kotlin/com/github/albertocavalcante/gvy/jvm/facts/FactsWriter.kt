// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

/** One section as it is stored in the file, with the length and checksum of its raw bytes. */
internal class StoredSection(
    val id: Int,
    val codec: Int,
    val stored: ByteArray,
    val rawLength: Int,
    val rawCrc32c: Int,
)

/**
 * Encodes the raw bytes of every section, in ascending id order. The result depends only on the content of
 * [facts]: lists are sorted by pool index, the pools are sorted by UTF-8 bytes, and nothing depends on a clock, a
 * hash order or the compression chosen later.
 */
internal fun encodeRawSections(facts: Facts): List<Pair<Int, ByteArray>> {
    val namePool = StringPoolWriter.of(collectNameStrings(facts))
    val typePool = StringPoolWriter.of(collectTypeStrings(facts))
    val classes = sortedClasses(facts.names, namePool)
    return listOf(
        SECTION_NAME_STRINGS to namePool.raw,
        SECTION_NAMES to encodeNames(facts.names, classes, namePool),
        SECTION_TYPE_STRINGS to typePool.raw,
        SECTION_TYPES to encodeTypes(facts.types, classes, typePool),
        SECTION_EXTENSIONS to encodeExtensions(facts.extensions, namePool),
    )
}

/** Writes facts files. */
public object FactsWriter {
    /**
     * Returns the bytes of a facts file for [facts].
     *
     * The same content gives the same raw section bytes, however the lists and strings were ordered when they were
     * built; with every section stored without compression the whole file is identical too. With DEFLATE, the
     * stored bytes are those of the JDK's zlib, so they can differ between JDK builds while the raw bytes do not.
     * A section that would compress beyond [FactsFormat.MAX_INFLATE_RATIO] is stored without compression, so that
     * every file written here passes the reader's ratio check.
     *
     * Throws [IllegalArgumentException] for a model the format cannot hold: a duplicate package, class, member or
     * extension, a class in a package the names section does not list, a [ClassTypes] for a class the names
     * section does not list, a string over [FactsFormat.MAX_STRING_BYTES] or a header string over
     * [FactsFormat.MAX_HEADER_STRING_BYTES] UTF-8 bytes, or text with an unpaired surrogate.
     */
    public fun write(facts: Facts, options: WriteOptions = WriteOptions()): ByteArray {
        val sections = encodeRawSections(facts).map { (id, raw) -> store(id, raw, options.encodingOf(id)) }
        val sink = ByteSink()
        writeHeader(sink, facts.header)
        sink.writeVarint(sections.size)
        for (section in sections) {
            sink.writeByte(section.id)
            sink.writeByte(section.codec)
            sink.writeLe(section.stored.size.toLong(), Int.SIZE_BYTES)
            sink.writeLe(section.rawLength.toLong(), Int.SIZE_BYTES)
            sink.writeLe(section.rawCrc32c.toLong(), Int.SIZE_BYTES)
        }
        for (section in sections) sink.writeBytes(section.stored)
        return sink.toByteArray()
    }
}

/**
 * Stores [raw] as [encoding] asks, except that a section a reader would refuse for its inflate ratio is stored as is:
 * highly repetitive content can compress beyond [FactsFormat.MAX_INFLATE_RATIO].
 */
private fun store(id: Int, raw: ByteArray, encoding: SectionEncoding): StoredSection {
    val crc = crc32c(raw, 0, raw.size)
    if (encoding.codec == CODEC_DEFLATE) {
        val deflated = deflate(raw, encoding.level)
        if (raw.size <= deflated.size.toLong() * FactsFormat.MAX_INFLATE_RATIO) {
            return StoredSection(id, CODEC_DEFLATE, deflated, raw.size, crc)
        }
    }
    return StoredSection(id, CODEC_NONE, raw, raw.size, crc)
}

private fun writeHeader(sink: ByteSink, header: FactsHeader) {
    sink.writeBytes(FactsFormat.MAGIC)
    sink.writeLe(FactsFormat.MAJOR.toLong(), Short.SIZE_BYTES)
    sink.writeLe(FactsFormat.MINOR.toLong(), Short.SIZE_BYTES)
    sink.writeByte(header.inputKind.code)
    sink.writeLe(header.inputLength, Long.SIZE_BYTES)
    sink.writeBytes(header.inputSha256.toByteArray())
    writeHeaderString(sink, header.producerVersion)
    val coordinate = header.coordinate
    if (coordinate == null) {
        sink.writeVarint(0)
    } else {
        val bytes = encodeHeaderString(coordinate)
        sink.writeVarint(bytes.size + 1)
        sink.writeBytes(bytes)
    }
}

private fun writeHeaderString(sink: ByteSink, text: String) {
    val bytes = encodeHeaderString(text)
    sink.writeVarint(bytes.size)
    sink.writeBytes(bytes)
}

private fun encodeHeaderString(text: String): ByteArray {
    val bytes = encodeUtf8Strict(text)
    require(bytes.size <= FactsFormat.MAX_HEADER_STRING_BYTES) {
        "A header string needs ${bytes.size} bytes; the limit is ${FactsFormat.MAX_HEADER_STRING_BYTES}"
    }
    return bytes
}
