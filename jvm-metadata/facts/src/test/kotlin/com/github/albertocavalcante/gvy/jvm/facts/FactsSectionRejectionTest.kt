// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Sections crafted byte by byte from FORMAT.md, each with the one defect a test is about. The base file has the
 * string "A" in both pools, one package "A", one class "A" and nothing else.
 */
class FactsSectionRejectionTest {
    // The largest value a varint holds, so a count of it cannot be satisfied by a successful allocation.
    private val hugeCount = Int.MAX_VALUE

    @Test
    fun `crafted sections without a defect load, so the rejections below are about their defect`() {
        val file = openFile(
            buildFile(
                sections(
                    types = typesSection(TypesRow()),
                    extensions = extensionsSection(ExtensionRow()),
                ),
            ),
        )

        val expectedNames = NamesSection(listOf(PackageFact("A", null)), listOf(ClassFact("A", "A", "A", null, 1)))
        val expectedMember = MemberFact(MemberKind.METHOD, 1, "A", "A")
        val expectedTypes = TypesSection(listOf(ClassTypes("A", 0, null, emptyList(), listOf(expectedMember))))
        val expectedExtension = ExtensionFact("A", ExtensionKind.STATIC, ExtensionSource.CORE_LIST, "A", "A")

        assertEquals(expectedNames, file.names().loaded())
        assertEquals(expectedTypes, file.types().loaded())
        assertEquals(ExtensionsSection(listOf(expectedExtension)), file.extensions().loaded())
    }

    @Test
    fun `an index outside the name pool in names is rejected`() {
        val broken = listOf(
            namesSection(classes = listOf(ClassRow(internalName = 1))),
            namesSection(classes = listOf(ClassRow(simpleName = 1))),
            namesSection(classes = listOf(ClassRow(outer = 2))),
            namesSection(packages = listOf(5)),
        )

        for (section in broken) {
            openFile(buildFile(sections(names = section))).names().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
        }
    }

    @Test
    fun `a package index outside the package table is rejected`() {
        val section = namesSection(classes = listOf(ClassRow(packageIndex = 1)))

        openFile(buildFile(sections(names = section))).names().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
    }

    @Test
    fun `an index outside the type pool or the class table in types is rejected`() {
        val broken = listOf(
            typesSection(TypesRow(superField = 2)),
            typesSection(TypesRow(interfaces = listOf(1))),
            typesSection(TypesRow(members = listOf(memberBytes(MemberRow(name = 1))))),
            typesSection(TypesRow(members = listOf(memberBytes(MemberRow(descriptor = 1))))),
            typesSection(TypesRow(members = listOf(memberBytes(MemberRow(parameterNames = listOf(0, 1)))))),
            typesSection(TypesRow(members = listOf(memberBytes(MemberRow(signature = 2))))),
            typesSection(TypesRow(classIndex = 1)),
        )

        for (section in broken) {
            openFile(buildFile(sections(types = section))).types().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
        }
    }

    @Test
    fun `an index outside the name pool in extensions is rejected`() {
        val broken = listOf(
            extensionsSection(ExtensionRow(holder = 1)),
            extensionsSection(ExtensionRow(moduleName = 1)),
            extensionsSection(ExtensionRow(moduleVersion = 1)),
        )

        for (section in broken) {
            openFile(buildFile(sections(extensions = section))).extensions()
                .assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
        }
    }

    @Test
    fun `a flag, release or code above its range is corrupt`() {
        val tooManyFlags = FactsFormat.MAX_ACCESS_FLAGS + 1
        val tooLateRelease = FactsFormat.MAX_RELEASE + 1
        val memberWithTooManyFlags = memberBytes(MemberRow(flags = tooManyFlags))
        val memberOfUnknownKind = memberBytes(MemberRow(kind = 2))

        val names = listOf(
            namesSection(classes = listOf(ClassRow(flags = tooManyFlags))),
            namesSection(classes = listOf(ClassRow(release = tooLateRelease))),
        )
        val types = listOf(
            typesSection(TypesRow(members = listOf(memberWithTooManyFlags))),
            typesSection(TypesRow(members = listOf(memberOfUnknownKind))),
        )
        val extensions = listOf(extensionsSection(ExtensionRow(kind = 2)), extensionsSection(ExtensionRow(source = 3)))

        for (section in names) {
            openFile(buildFile(sections(names = section))).names().assertRejected(RejectReason.CORRUPT)
        }
        for (section in types) {
            openFile(buildFile(sections(types = section))).types().assertRejected(RejectReason.CORRUPT)
        }
        for (section in extensions) {
            openFile(buildFile(sections(extensions = section))).extensions().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `a count larger than the remaining bytes is rejected before anything is allocated or charged for it`() {
        val brokenMember = memberBytes(MemberRow()).copyOf(4) + bytes { varint(hugeCount) }
        val broken = listOf(
            sections(nameStrings = bytes { varint(hugeCount) }),
            sections(names = bytes { varint(hugeCount) }),
            sections(names = bytes { varint(0).varint(hugeCount) }),
            sections(extensions = bytes { varint(hugeCount) }),
            sections(typeStrings = bytes { varint(hugeCount) }),
            sections(types = bytes { varint(hugeCount) }),
            sections(types = bytes { varint(1).varint(0).varint(0).varint(hugeCount) }),
            sections(types = bytes { varint(1).varint(0).varint(0).varint(0).varint(hugeCount) }),
            sections(types = bytes { varint(1).varint(0).varint(0).varint(0).varint(1).raw(brokenMember) }),
        )

        for (file in broken) {
            openFile(buildFile(file)).loadAll().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `a pooled string that is not valid UTF-8 is corrupt in either pool`() {
        val invalid = listOf(
            byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        )

        for (entry in invalid) {
            openFile(buildFile(sections(nameStrings = poolOfBytes(entry)))).names().assertRejected(RejectReason.CORRUPT)
            openFile(buildFile(sections(typeStrings = poolOfBytes(entry)))).types().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `NUL and noncharacters are valid pool strings`() {
        val pool = poolOf("\u0000", "\uFDD0", "\uFFFE")

        openFile(buildFile(sections(nameStrings = pool, typeStrings = pool))).loadAll().loaded()
    }

    @Test
    fun `a pooled string above the length limit is oversized and one at the limit loads`() {
        val longest = ByteArray(FactsFormat.MAX_STRING_BYTES) { 'a'.code.toByte() }
        val tooLong = longest + byteArrayOf('a'.code.toByte())

        openFile(buildFile(sections(nameStrings = poolOfBytes(longest)))).names().loaded()
        openFile(buildFile(sections(typeStrings = poolOfBytes(longest)))).types().loaded()
        openFile(buildFile(sections(nameStrings = poolOfBytes(tooLong)))).names().assertRejected(RejectReason.OVERSIZED)
        openFile(buildFile(sections(typeStrings = poolOfBytes(tooLong)))).types().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `a pool that is not strictly increasing by UTF-8 bytes is corrupt`() {
        // U+FF5E is EF BD 9E and U+1F600 is F0 9F 98 80: the emoji sorts after in bytes, before in UTF-16.
        // "a" is a prefix of "ab", so it sorts first.
        val unsorted = listOf(poolOf("b", "a"), poolOf("a", "a"), poolOf("😀", "～"), poolOf("ab", "a"))
        val sorted = listOf(poolOf("a", "b"), poolOf("～", "😀"), poolOf("a", "ab"))

        for (pool in unsorted) {
            openFile(buildFile(sections(nameStrings = pool))).names().assertRejected(RejectReason.CORRUPT)
            openFile(buildFile(sections(typeStrings = pool))).types().assertRejected(RejectReason.CORRUPT)
        }
        for (pool in sorted) {
            openFile(buildFile(sections(nameStrings = pool, typeStrings = pool))).loadAll().loaded()
        }
    }

    @Test
    fun `a malformed varint is corrupt`() {
        // The last case is a non-minimal zero followed by a valid empty class count, so only minimality rejects it.
        val malformed = listOf(
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x08),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0x80.toByte(), 0x00, 0x00),
        )

        for (raw in malformed) {
            openFile(buildFile(sections(names = raw))).names().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `the largest varint is accepted and then checked as the index it is`() {
        val largest = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07)
        val names = bytes { varint(1).raw(largest).varint(0).varint(0) }

        openFile(buildFile(sections(names = names))).names().assertRejected(RejectReason.INDEX_OUT_OF_RANGE)
    }

    @Test
    fun `a section that ends in the middle of an item or has bytes left over is corrupt`() {
        val names = namesSection()
        val types = typesSection(TypesRow())
        val extensions = extensionsSection(ExtensionRow())
        val cutNames = names.copyOf(names.size - 1)
        val cutTypes = types.copyOf(types.size - 1)
        val cutExtensions = extensions.copyOf(extensions.size - 1)
        val trailing = byteArrayOf(0)

        val broken = listOf(
            sections(names = cutNames),
            sections(names = names + trailing),
            sections(types = cutTypes),
            sections(types = types + trailing),
            sections(extensions = cutExtensions),
            sections(extensions = extensions + trailing),
            sections(nameStrings = poolOf("A") + trailing),
            sections(typeStrings = poolOf("A") + trailing),
        )

        for (file in broken) {
            openFile(buildFile(file)).loadAll().assertRejected(RejectReason.CORRUPT)
        }
    }
}
