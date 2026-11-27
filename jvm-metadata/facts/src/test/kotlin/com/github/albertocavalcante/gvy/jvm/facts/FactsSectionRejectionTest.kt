// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Sections crafted byte by byte from FORMAT.md, each with the one defect a test is about. */
class FactsSectionRejectionTest {
    private val hugeCount = 1_000_000_000
    private val tinyLimits = ReadLimits(maxFileBytes = 4096, maxSectionBytes = 64)

    @Test
    fun `crafted sections without a defect load, so the rejections below are about their defect`() {
        val file = openFile(buildFile(sections(poolOf("A"), names(), types(), extensions())))

        val expectedNames = NamesSection(listOf(PackageFact("A", null)), listOf(ClassFact("A", "A", "A", null, 1)))
        val expectedMember = MemberFact(MemberKind.METHOD, 1, "A", "A")
        val expectedTypes = TypesSection(listOf(ClassTypes("A", 0, null, emptyList(), listOf(expectedMember))))

        assertEquals(expectedNames, file.names().loaded())
        assertEquals(expectedTypes, file.types().loaded())
        assertEquals(
            ExtensionsSection(listOf(ExtensionFact("A", ExtensionKind.STATIC, ExtensionSource.CORE_LIST, "A", "A"))),
            file.extensions().loaded(),
        )
    }

    @Test
    fun `an index outside the string pool in names is rejected`() {
        val broken = listOf(
            names(ClassRow(internalName = 1)),
            names(ClassRow(simpleName = 1)),
            names(ClassRow(outer = 2)),
        )

        for (section in broken) {
            decode(NAMES, section).names().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
        }
        decode(NAMES, bytes { varint(1).varint(5).varint(0) }).names().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
    }

    @Test
    fun `a package index outside the package table is rejected`() {
        decode(NAMES, names(ClassRow(packageIndex = 1))).names().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
    }

    @Test
    fun `an index outside the string pool in types is rejected`() {
        val broken = listOf(
            types(superField = 2),
            types(interfaces = listOf(1)),
            types(memberBytes = member(MemberRow(name = 1))),
            types(memberBytes = member(MemberRow(descriptor = 1))),
            types(memberBytes = member(MemberRow(parameterNames = listOf(0, 1)))),
            types(memberBytes = member(MemberRow(signature = 2))),
        )

        for (section in broken) {
            decode(TYPES, section).types().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
        }
    }

    @Test
    fun `an index outside the string pool in extensions is rejected`() {
        for (broken in listOf(extensions(holder = 1), extensions(moduleName = 1), extensions(moduleVersion = 1))) {
            decode(EXTENSIONS, broken).extensions().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
        }
    }

    @Test
    fun `a flag, release or code above its range is corrupt`() {
        val tooManyFlags = FactsFormat.MAX_ACCESS_FLAGS + 1
        val tooLateRelease = FactsFormat.MAX_RELEASE + 1
        val memberWithTooManyFlags = member(MemberRow(flags = tooManyFlags))
        val memberOfUnknownKind = member(MemberRow(kind = 2))

        decode(NAMES, names(ClassRow(flags = tooManyFlags))).names().assertRejected(RejectReason.CORRUPT)
        decode(NAMES, names(ClassRow(release = tooLateRelease))).names().assertRejected(RejectReason.CORRUPT)
        decode(TYPES, types(memberBytes = memberWithTooManyFlags)).types().assertRejected(RejectReason.CORRUPT)
        decode(TYPES, types(release = tooLateRelease)).types().assertRejected(RejectReason.CORRUPT)
        decode(TYPES, types(memberBytes = memberOfUnknownKind)).types().assertRejected(RejectReason.CORRUPT)
        decode(EXTENSIONS, extensions(kind = 2)).extensions().assertRejected(RejectReason.CORRUPT)
        decode(EXTENSIONS, extensions(source = 3)).extensions().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a count larger than the remaining bytes is rejected without allocating for it`() {
        val broken = listOf(
            NAMES to bytes { varint(hugeCount) },
            NAMES to bytes { varint(0).varint(hugeCount) },
            NAMES to bytes { varint(Int.MAX_VALUE) },
            TYPES to bytes { varint(hugeCount) },
            TYPES to bytes { varint(1).varint(0).varint(0).varint(0).varint(hugeCount) },
            TYPES to bytes { varint(1).varint(0).varint(0).varint(0).varint(0).varint(hugeCount) },
            TYPES to types(memberBytes = bytes { u8(1).varint(1).varint(0).varint(0).varint(hugeCount) }),
            EXTENSIONS to bytes { varint(hugeCount) },
        )

        for ((id, raw) in broken) {
            decode(id, raw, tinyLimits).loadAll().assertRejected(RejectReason.CORRUPT)
        }
        decode(STRINGS, bytes { varint(hugeCount) }, tinyLimits).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `a pooled string that is not valid UTF-8 is corrupt`() {
        val invalid = listOf(
            byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()),
        )

        for (entry in invalid) {
            decode(STRINGS, poolOfBytes(entry)).names().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `a pooled string above the length limit is oversized and one at the limit loads`() {
        val longest = ByteArray(FactsFormat.MAX_STRING_BYTES) { 'a'.code.toByte() }

        decode(STRINGS, poolOfBytes(longest)).names().loaded()
        val tooLong = longest + byteArrayOf('a'.code.toByte())

        decode(STRINGS, poolOfBytes(tooLong)).names().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `a pool that is not strictly increasing by UTF-8 bytes is corrupt`() {
        // U+FF5E is EF BD 9E and U+1F600 is F0 9F 98 80: the emoji sorts after in bytes, before in UTF-16.
        val unsorted = listOf(poolOf("b", "a"), poolOf("a", "a"), poolOf("😀", "～"))

        for (pool in unsorted) {
            decode(STRINGS, pool).names().assertRejected(RejectReason.CORRUPT)
        }
        decode(STRINGS, poolOf("a", "b")).names().loaded()
        decode(STRINGS, poolOf("～", "😀")).names().loaded()
    }

    @Test
    fun `a malformed varint is corrupt`() {
        val malformed = listOf(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01),
            byteArrayOf(0x80.toByte(), 0x00),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x08),
            byteArrayOf(0x80.toByte()),
        )

        for (raw in malformed) {
            decode(NAMES, raw).names().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `a section that ends in the middle of an item or has bytes left over is corrupt`() {
        val cut = names().copyOf(names().size - 1)
        val extra = names() + byteArrayOf(0)

        decode(NAMES, cut).names().assertRejected(RejectReason.CORRUPT)
        decode(NAMES, extra).names().assertRejected(RejectReason.CORRUPT)
        decode(TYPES, types() + byteArrayOf(0)).types().assertRejected(RejectReason.CORRUPT)
        decode(EXTENSIONS, extensions() + byteArrayOf(0)).extensions().assertRejected(RejectReason.CORRUPT)
        decode(STRINGS, poolOf("A") + byteArrayOf(0)).names().assertRejected(RejectReason.CORRUPT)
    }

    /** A file whose pool is "A" and whose [id] section is [raw]; the others are empty. */
    private fun decode(id: Int, raw: ByteArray, limits: ReadLimits = ReadLimits()): FactsFile {
        val pool = if (id == STRINGS) raw else poolOf("A")
        val replaced = sections(pool).map { if (it.id == id) plain(id, raw) else it }
        return openFile(buildFile(replaced), limits)
    }

    /** One package named by pool string 0 and one class; every field of [row] is an index or value to break. */
    private fun names(row: ClassRow = ClassRow()): ByteArray = bytes {
        varint(1).varint(0).varint(0)
        varint(1).varint(row.internalName).varint(row.packageIndex).varint(row.simpleName)
        varint(row.outer).varint(row.flags).varint(row.release)
    }

    /** One class named by pool string 0 with [memberBytes] as its only member. */
    private fun types(
        release: Int = 0,
        superField: Int = 0,
        interfaces: List<Int> = emptyList(),
        memberBytes: ByteArray = member(),
    ): ByteArray = bytes {
        varint(1).varint(0).varint(release).varint(superField).varint(interfaces.size)
        interfaces.forEach { varint(it) }
        varint(1).raw(memberBytes)
    }

    private fun member(row: MemberRow = MemberRow()): ByteArray = bytes {
        u8(row.kind).varint(row.flags).varint(row.name).varint(row.descriptor)
        val parameterNames = row.parameterNames
        if (parameterNames == null) varint(0) else varint(parameterNames.size + 1)
        parameterNames?.forEach { varint(it) }
        varint(row.signature)
    }

    private fun extensions(
        holder: Int = 0,
        kind: Int = 1,
        source: Int = 2,
        moduleName: Int = 0,
        moduleVersion: Int = 0,
    ): ByteArray = bytes {
        varint(1).varint(holder).u8(kind).u8(source).varint(moduleName).varint(moduleVersion)
    }

    /** The six fields of the class row in [names], as pool indices or values. */
    private class ClassRow(
        val internalName: Int = 0,
        val packageIndex: Int = 0,
        val simpleName: Int = 0,
        val outer: Int = 0,
        val flags: Int = 1,
        val release: Int = 0,
    )

    /** The fields of a member in [member]: a method named by pool string 0, without parameter names or signature. */
    private class MemberRow(
        val kind: Int = 1,
        val flags: Int = 1,
        val name: Int = 0,
        val descriptor: Int = 0,
        val parameterNames: List<Int>? = null,
        val signature: Int = 0,
    )
}
