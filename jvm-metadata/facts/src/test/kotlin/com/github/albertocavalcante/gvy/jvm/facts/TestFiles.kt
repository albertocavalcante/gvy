// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32C
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/** Section ids and codecs as FORMAT.md states them, repeated here so the tests do not share code with the module. */
internal const val NAME_STRINGS = 1
internal const val NAMES = 2
internal const val EXTENSIONS = 3
internal const val TYPE_STRINGS = 4
internal const val TYPES = 5
internal const val STORED = 0
internal const val ZLIB = 1

internal fun sampleSha(seed: Int = 1): Sha256 =
    Sha256.of(ByteArray(Sha256.SIZE_BYTES) { index -> (index + seed).toByte() })

internal fun sampleHeader(): FactsHeader = FactsHeader(
    producerVersion = "gvy-test 1.0",
    inputKind = InputKind.JAR,
    coordinate = "org.example:demo:1.0",
    inputSha256 = sampleSha(),
    inputLength = 123_456L,
)

/** A model with every section, written in the order the writer sorts into, so a round trip compares equal. */
internal fun sampleFacts(): Facts = Facts(
    header = sampleHeader(),
    names = sampleNames(),
    types = sampleTypes(),
    extensions = sampleExtensions(),
)

private fun sampleNames(): NamesSection = NamesSection(
    packages = listOf(PackageFact("com.example", null), PackageFact("java.util", "java.base")),
    classes = listOf(
        ClassFact("com/example/Outer", "com.example", "Outer", null, 0x21),
        ClassFact("com/example/Outer\$Inner", "com.example", "Inner", "com/example/Outer", 0x9),
        ClassFact("java/util/List", "java.util", "List", null, 0x601),
        ClassFact("java/util/List", "java.util", "List", null, 0x601, release = 9),
    ),
)

private fun sampleTypes(): TypesSection {
    val outer = ClassTypes(
        internalName = "com/example/Outer",
        release = 0,
        superName = "java/lang/Object",
        interfaces = listOf("java/lang/Comparable", "java/io/Serializable"),
        members = listOf(
            MemberFact(MemberKind.FIELD, FactsFormat.DEPRECATED_FLAG or 0x19, "COUNT", "I"),
            MemberFact(
                kind = MemberKind.METHOD,
                accessFlags = 0x1,
                name = "compare",
                descriptor = "(Lcom/example/Outer;Lcom/example/Outer;)I",
                parameterNames = listOf("right", "left"),
                genericSignature = "<T:Ljava/lang/Object;>(TT;TT;)I",
            ),
            MemberFact(MemberKind.METHOD, 0x1, "run", "()V", parameterNames = emptyList()),
            MemberFact(MemberKind.METHOD, FactsFormat.DEPRECATED_FLAG or 0x1, "run", "(I)V"),
        ),
    )
    val inner = ClassTypes("com/example/Outer\$Inner", 0, "java/lang/Object", emptyList(), emptyList())
    val list = ClassTypes(
        internalName = "java/util/List",
        release = 0,
        superName = null,
        interfaces = listOf("java/util/Collection"),
        members = listOf(MemberFact(MemberKind.METHOD, 0x401, "size", "()I")),
    )
    return TypesSection(listOf(outer, inner, list))
}

private fun sampleExtensions(): ExtensionsSection {
    val methods = "org/codehaus/groovy/runtime/DefaultGroovyMethods"
    val staticMethods = "org/codehaus/groovy/runtime/DefaultGroovyStaticMethods"
    return ExtensionsSection(
        listOf(
            ExtensionFact(methods, ExtensionKind.INSTANCE, ExtensionSource.CORE_LIST, "groovy", "4.0.33"),
            ExtensionFact(staticMethods, ExtensionKind.STATIC, ExtensionSource.CORE_LIST, "groovy", "4.0.33"),
            ExtensionFact("org/example/Ext", ExtensionKind.INSTANCE, ExtensionSource.META_INF_GROOVY, "ext", "1.0"),
        ),
    )
}

internal fun emptyFacts(): Facts = Facts(
    header = FactsHeader("", InputKind.JDK_IMAGE, null, sampleSha(), 0L),
    names = NamesSection(emptyList(), emptyList()),
    types = TypesSection(emptyList()),
    extensions = ExtensionsSection(emptyList()),
)

/** The same content with every order the writer is free to choose shuffled. Interface order is meaningful. */
internal fun shuffled(facts: Facts, random: Random): Facts = Facts(
    header = facts.header,
    names = NamesSection(facts.names.packages.shuffled(random), facts.names.classes.shuffled(random)),
    types = TypesSection(
        facts.types.classes.shuffled(random).map { fact -> fact.copy(members = fact.members.shuffled(random)) },
    ),
    extensions = ExtensionsSection(facts.extensions.entries.shuffled(random)),
)

/** The class names of [classes] listed in the names section, in package `p`, so that their types can be written. */
internal fun factsWithTypes(vararg classes: ClassTypes): Facts = emptyFacts().copy(
    names = NamesSection(
        packages = listOf(PackageFact("p", null)),
        classes = classes.map { fact -> ClassFact(fact.internalName, "p", "C", null, 1, fact.release) },
    ),
    types = TypesSection(classes.toList()),
)

internal fun writeWith(facts: Facts, encoding: SectionEncoding): ByteArray =
    FactsWriter.write(facts, WriteOptions(encoding, encoding, encoding, encoding, encoding))

internal fun <T> LoadResult<T>.loaded(): T = when (this) {
    is LoadResult.Loaded -> value
    is LoadResult.Rejected -> fail("Expected the input to load, but it was rejected: $reason")
}

internal fun LoadResult<*>.bytesRead(): Int =
    assertIs<LoadResult.Loaded<*>>(this, "Expected the input to load").bytesRead

internal fun LoadResult<*>.assertRejected(reason: RejectReason) {
    assertEquals(reason, assertIs<LoadResult.Rejected>(this, "Expected a rejection").reason)
}

internal fun openFile(bytes: ByteArray, limits: ReadLimits = ReadLimits()): FactsFile =
    FactsFile.open(bytes, limits).loaded()

/** Builds bytes the way FORMAT.md describes them, independently of the module's own writer. */
internal class TestBytes {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int): TestBytes = apply { out.write(value) }

    fun raw(bytes: ByteArray): TestBytes = apply { out.write(bytes, 0, bytes.size) }

    fun le(value: Long, width: Int): TestBytes = apply {
        repeat(width) { index -> out.write(((value ushr (8 * index)) and 0xFF).toInt()) }
    }

    fun varint(value: Int): TestBytes = apply {
        var rest = value
        while (rest >= 0x80) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }

    fun utf8(text: String): TestBytes = apply {
        val bytes = text.toByteArray(Charsets.UTF_8)
        varint(bytes.size)
        raw(bytes)
    }

    fun build(): ByteArray = out.toByteArray()
}

internal fun bytes(block: TestBytes.() -> Unit): ByteArray = TestBytes().apply(block).build()

internal fun poolOf(vararg sortedStrings: String): ByteArray = bytes {
    varint(sortedStrings.size)
    sortedStrings.forEach { utf8(it) }
}

/** A pool whose entries are raw byte strings, for input that is not valid UTF-8 or not sorted. */
internal fun poolOfBytes(vararg entries: ByteArray): ByteArray = bytes {
    varint(entries.size)
    entries.forEach { entry ->
        varint(entry.size)
        raw(entry)
    }
}

internal fun crcOf(raw: ByteArray): Int = CRC32C().apply { update(raw, 0, raw.size) }.value.toInt()

internal fun zlib(raw: ByteArray): ByteArray {
    val deflater = Deflater(6)
    deflater.setInput(raw)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return out.toByteArray()
}

/** One row of a section table together with its stored bytes, for crafting files. */
internal class TestSection(val id: Int, val codec: Int, val stored: ByteArray, val rawLength: Int, val crc: Int)

internal fun plain(id: Int, raw: ByteArray): TestSection = TestSection(id, STORED, raw, raw.size, crcOf(raw))

/** A DEFLATE section whose table row declares [declaredRawLength], which may differ from the truth. */
internal fun deflated(id: Int, raw: ByteArray, declaredRawLength: Int = raw.size, crc: Int = crcOf(raw)): TestSection =
    TestSection(id, ZLIB, zlib(raw), declaredRawLength, crc)

/** The sections of a valid file: pool string "A" in both pools, one package "A", one class "A", nothing else. */
internal fun sections(
    nameStrings: ByteArray = poolOf("A"),
    names: ByteArray = namesSection(),
    extensions: ByteArray = extensionsSection(),
    typeStrings: ByteArray = poolOf("A"),
    types: ByteArray = typesSection(),
): List<TestSection> = listOf(
    plain(NAME_STRINGS, nameStrings),
    plain(NAMES, names),
    plain(EXTENSIONS, extensions),
    plain(TYPE_STRINGS, typeStrings),
    plain(TYPES, types),
)

/** The fields of one class row of a names section: pool indices, or values to break. */
internal class ClassRow(
    val internalName: Int = 0,
    val packageIndex: Int = 0,
    val simpleName: Int = 0,
    val outer: Int = 0,
    val flags: Int = 1,
    val release: Int = 0,
)

/** The fields of one member of a types section: a method named by type string 0 unless told otherwise. */
internal class MemberRow(
    val kind: Int = 1,
    val flags: Int = 1,
    val name: Int = 0,
    val descriptor: Int = 0,
    val parameterNames: List<Int>? = null,
    val signature: Int = 0,
)

/** One class of a types section: an index into the names section's classes, then its supertypes and members. */
internal class TypesRow(
    val classIndex: Int = 0,
    val superField: Int = 0,
    val interfaces: List<Int> = emptyList(),
    val members: List<ByteArray> = listOf(memberBytes()),
)

/** One entry of an extensions section, as name pool indices and codes. */
internal class ExtensionRow(
    val holder: Int = 0,
    val kind: Int = 1,
    val source: Int = 2,
    val moduleName: Int = 0,
    val moduleVersion: Int = 0,
)

/** A names section: packages named by the given pool indices without a module, then [classes]. */
internal fun namesSection(packages: List<Int> = listOf(0), classes: List<ClassRow> = listOf(ClassRow())): ByteArray =
    bytes {
        varint(packages.size)
        packages.forEach { name -> varint(name).varint(0) }
        varint(classes.size)
        classes.forEach { row ->
            varint(row.internalName).varint(row.packageIndex).varint(row.simpleName)
            varint(row.outer).varint(row.flags).varint(row.release)
        }
    }

internal fun memberBytes(row: MemberRow = MemberRow()): ByteArray = bytes {
    u8(row.kind).varint(row.flags).varint(row.name).varint(row.descriptor)
    val parameterNames = row.parameterNames
    if (parameterNames == null) varint(0) else varint(parameterNames.size + 1)
    parameterNames?.forEach { varint(it) }
    varint(row.signature)
}

internal fun typesSection(vararg rows: TypesRow): ByteArray = bytes {
    varint(rows.size)
    rows.forEach { row ->
        varint(row.classIndex).varint(row.superField).varint(row.interfaces.size)
        row.interfaces.forEach { varint(it) }
        varint(row.members.size)
        row.members.forEach { raw(it) }
    }
}

internal fun extensionsSection(vararg rows: ExtensionRow): ByteArray = bytes {
    varint(rows.size)
    rows.forEach { row ->
        varint(row.holder).u8(row.kind).u8(row.source).varint(row.moduleName).varint(row.moduleVersion)
    }
}

internal fun buildFile(
    sections: List<TestSection>,
    major: Int = 1,
    minor: Int = 0,
    inputKind: Int = 1,
    inputLength: Long = 0L,
): ByteArray = bytes {
    raw("GVYF".toByteArray(Charsets.US_ASCII))
    le(major.toLong(), 2)
    le(minor.toLong(), 2)
    u8(inputKind)
    le(inputLength, 8)
    raw(sampleSha().toByteArray())
    utf8("test")
    varint(0)
    varint(sections.size)
    sections.forEach { section ->
        u8(section.id)
        u8(section.codec)
        le(section.stored.size.toLong(), 4)
        le(section.rawLength.toLong(), 4)
        le(section.crc.toLong(), 4)
    }
    sections.forEach { section -> raw(section.stored) }
}

/** Where a section's table row and stored bytes are in a written file. */
internal data class SectionLocation(
    val id: Int,
    val entryOffset: Int,
    val offset: Int,
    val codec: Int,
    val stored: Int,
    val raw: Int,
    val crc: Int,
)

private class Cursor(val bytes: ByteArray, var position: Int) {
    fun u8(): Int = bytes[position++].toInt() and 0xFF

    fun varint(): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = u8()
            value = value or ((byte and 0x7F) shl shift)
            if (byte < 0x80) return value
            shift += 7
        }
    }

    fun le(width: Int): Long {
        var value = 0L
        repeat(width) { index -> value = value or (u8().toLong() shl (8 * index)) }
        return value
    }
}

internal fun sectionLocations(file: ByteArray): List<SectionLocation> {
    val cursor = Cursor(file, 4 + 2 + 2 + 1 + 8 + Sha256.SIZE_BYTES)
    val producerLength = cursor.varint()
    cursor.position += producerLength
    val coordinate = cursor.varint()
    if (coordinate > 0) cursor.position += coordinate - 1
    val count = cursor.varint()
    var offset = cursor.position + count * 14
    return List(count) {
        val entryOffset = cursor.position
        val id = cursor.u8()
        val codec = cursor.u8()
        val stored = cursor.le(4).toInt()
        val raw = cursor.le(4).toInt()
        val crc = cursor.le(4).toInt()
        SectionLocation(id, entryOffset, offset, codec, stored, raw, crc).also { offset += stored }
    }
}

/** The raw bytes of every section of a valid file, inflated with the JDK directly, not through the module. */
internal fun rawSections(file: ByteArray): Map<Int, ByteArray> = sectionLocations(file).associate { location ->
    val stored = file.copyOfRange(location.offset, location.offset + location.stored)
    val raw = if (location.codec == ZLIB) InflaterInputStream(stored.inputStream()).use { it.readBytes() } else stored
    location.id to raw
}

internal fun assertSameRawSections(expected: ByteArray, actual: ByteArray) {
    val left = rawSections(expected)
    val right = rawSections(actual)
    assertEquals(left.keys, right.keys)
    for ((id, raw) in left) {
        assertContentEquals(raw, right.getValue(id), "raw bytes of section $id")
    }
}

/** The pooled strings of pool section [id] of a file whose sections are stored without compression. */
internal fun readPool(file: ByteArray, id: Int): List<String> {
    val location = sectionLocations(file).single { it.id == id }
    val cursor = Cursor(file, location.offset)
    return List(cursor.varint()) {
        val length = cursor.varint()
        String(file, cursor.position, length, Charsets.UTF_8).also { cursor.position += length }
    }
}

internal fun ByteArray.withByte(index: Int, value: Int): ByteArray = copyOf().also { it[index] = value.toByte() }

internal fun ByteArray.withFlippedByte(index: Int): ByteArray = withByte(index, this[index].toInt() xor 0xFF)

/** The decoded items [FactsFile.names] charges for [facts]: both counts and the name pool of a stored [file]. */
internal fun namesItems(facts: Facts, file: ByteArray): Int =
    readPool(file, NAME_STRINGS).size + facts.names.packages.size + facts.names.classes.size

internal fun extensionItems(facts: Facts): Int = facts.extensions.entries.size

/** The items types charge beyond the names: the type pool, classes, interfaces, members and parameter names. */
internal fun typesItems(facts: Facts, file: ByteArray): Int {
    val classes = facts.types.classes
    val interfaces = classes.sumOf { it.interfaces.size }
    val members = classes.sumOf { it.members.size }
    val parameterNames = classes.sumOf { fact -> fact.members.sumOf { member -> member.parameterNames?.size ?: 0 } }
    return readPool(file, TYPE_STRINGS).size + classes.size + interfaces + members + parameterNames
}
