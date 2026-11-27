// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import java.util.Arrays
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FactsRoundTripTest {
    private val encodings = mapOf(
        "stored" to SectionEncoding.NONE,
        "deflate 1" to SectionEncoding.deflate(1),
        "deflate 6" to SectionEncoding.deflate(6),
        "deflate 9" to SectionEncoding.deflate(9),
    )

    @Test
    fun `a model with every section round trips under each codec`() {
        val facts = sampleFacts()

        for ((label, encoding) in encodings) {
            val file = openFile(writeWith(facts, encoding))
            assertEquals(facts, file.loadAll().loaded(), label)
            assertEquals(facts.header, file.header, label)
            assertEquals(FactsFormat.MINOR, file.formatMinor, label)
        }
    }

    @Test
    fun `each section can use its own codec`() {
        val facts = sampleFacts()
        val options = WriteOptions(
            nameStrings = SectionEncoding.NONE,
            names = SectionEncoding.deflate(9),
            extensions = SectionEncoding.deflate(1),
            typeStrings = SectionEncoding.NONE,
            types = SectionEncoding.deflate(6),
        )

        val bytes = FactsWriter.write(facts, options)

        assertEquals(facts, openFile(bytes).loadAll().loaded())
        val codecs = sectionLocations(bytes).associate { it.id to it.codec }
        val expected = mapOf(
            NAME_STRINGS to STORED,
            NAMES to ZLIB,
            EXTENSIONS to ZLIB,
            TYPE_STRINGS to STORED,
            TYPES to ZLIB,
        )
        assertEquals(expected, codecs)
    }

    @Test
    fun `an empty model round trips and the header keeps an absent coordinate absent`() {
        val facts = emptyFacts()

        for ((label, encoding) in encodings) {
            val loaded = openFile(writeWith(facts, encoding)).loadAll().loaded()
            assertEquals(facts, loaded, label)
            assertEquals(null, loaded.header.coordinate, label)
        }
    }

    @Test
    fun `the writer emits all five sections in ascending id order at the current version`() {
        val bytes = writeWith(sampleFacts(), SectionEncoding.NONE)

        assertEquals(
            listOf(NAME_STRINGS, NAMES, EXTENSIONS, TYPE_STRINGS, TYPES),
            sectionLocations(bytes).map {
                it.id
            },
        )
        assertEquals(FactsFormat.MAJOR, bytes[4].toInt() + (bytes[5].toInt() shl 8))
        assertEquals(FactsFormat.MINOR, bytes[6].toInt() + (bytes[7].toInt() shl 8))
    }

    @Test
    fun `the table checksum of each section is the CRC-32C of its raw bytes`() {
        // The CRC-32C check value of "123456789" is 0xE3069283.
        assertEquals(0xE3069283L, crcOf("123456789".toByteArray()).toLong() and 0xFFFFFFFFL)
        val bytes = writeWith(sampleFacts(), SectionEncoding.deflate(6))
        val raw = rawSections(bytes)

        for (location in sectionLocations(bytes)) {
            val expected = raw.getValue(location.id)
            assertEquals(crcOf(expected), location.crc, "section ${location.id}")
            assertEquals(expected.size, location.raw, "section ${location.id}")
        }
    }

    @Test
    fun `reported byte counts add up to the file and names need neither the type pool nor the types`() {
        val bytes = writeWith(sampleFacts(), SectionEncoding.deflate(6))
        val stored = sectionLocations(bytes).associate { it.id to it.stored }

        val opened = FactsFile.open(bytes)
        val file = opened.loaded()

        assertEquals(bytes.size, opened.bytesRead() + file.loadAll().bytesRead())
        assertEquals(stored.getValue(NAME_STRINGS) + stored.getValue(NAMES), file.names().bytesRead())
        val extensionsNeed = listOf(NAME_STRINGS, NAMES, EXTENSIONS).sumOf { stored.getValue(it) }
        assertEquals(extensionsNeed, file.extensions().bytesRead())
        assertEquals(stored.values.sum(), file.types().bytesRead())
    }

    @Test
    fun `a model from any insertion order is written sorted`() {
        val expected = sampleFacts()

        for (seed in 1..20) {
            val permuted = shuffled(expected, Random(seed))
            val loaded = openFile(writeWith(permuted, SectionEncoding.NONE)).loadAll().loaded()
            assertEquals(expected, loaded, "seed $seed")
        }
    }

    @Test
    fun `interface order and parameter name order are kept because they are meaningful`() {
        val loaded = openFile(writeWith(sampleFacts(), SectionEncoding.NONE)).types().loaded()
        val outer = loaded.classes.single { it.internalName == "com/example/Outer" }

        assertEquals(listOf("java/lang/Comparable", "java/io/Serializable"), outer.interfaces)
        assertEquals(listOf("right", "left"), outer.members.single { it.name == "compare" }.parameterNames)
        assertEquals(emptyList<String>(), outer.members.single { it.descriptor == "()V" }.parameterNames)
        assertEquals(null, outer.members.single { it.descriptor == "(I)V" }.parameterNames)
    }

    @Test
    fun `strings with accents, CJK and supplementary plane characters round trip`() {
        val strings = listOf("Ünïcode", "日本語", "～", "😀", "a\u0000b", "😀😁")
        val className = "p/${strings[1]}"
        val facts = emptyFacts().copy(
            names = NamesSection(
                packages = strings.map { PackageFact(it, "m-$it") },
                classes = listOf(ClassFact(className, strings[1], strings[1], null, 1)),
            ),
            types = TypesSection(listOf(ClassTypes(className, 0, strings[3], strings, emptyList()))),
        )

        val loaded = openFile(writeWith(facts, SectionEncoding.deflate(6))).loadAll().loaded()

        assertEquals(facts.names.packages.toSet(), loaded.names.packages.toSet())
        assertEquals(facts.names.classes, loaded.names.classes)
        assertEquals(facts.types, loaded.types)
    }

    @Test
    fun `each string pool is sorted by UTF-8 bytes and not by UTF-16 units`() {
        // U+FF5E is EF BD 9E and U+1F600 is F0 9F 98 80, so the byte order puts the emoji last.
        // In UTF-16 the emoji starts with the surrogate D83D, which sorts before U+FF5E.
        val member = MemberFact(MemberKind.FIELD, 1, "😀", "～")
        val facts = emptyFacts().copy(
            names = NamesSection(
                listOf(PackageFact("～", null), PackageFact("😀", null)),
                listOf(ClassFact("～/C", "～", "C", null, 1)),
            ),
            types = TypesSection(listOf(ClassTypes("～/C", 0, "😀", listOf("～"), listOf(member)))),
        )

        val bytes = writeWith(facts, SectionEncoding.NONE)

        for (id in listOf(NAME_STRINGS, TYPE_STRINGS)) {
            val pool = readPool(bytes, id)
            assertTrue("～" in pool && "😀" in pool, "pool $id")
            assertEquals(pool.sortedWith { left, right -> compareUtf8(left, right) }, pool)
            assertTrue(pool.indexOf("～") < pool.indexOf("😀"), "pool $id")
        }
    }

    @Test
    fun `strings and header strings of exactly the maximum length round trip`() {
        // Seeded letters, not one repeated character: a repeated character deflates beyond the inflate ratio cap.
        val random = Random(7)
        val letters = CharArray(FactsFormat.MAX_STRING_BYTES) { random.nextInt('a'.code, 'z'.code + 1).toChar() }
        val longest = String(letters)
        val headerLongest = "h".repeat(FactsFormat.MAX_HEADER_STRING_BYTES)
        val base = factsWithTypes(ClassTypes(longest, FactsFormat.MAX_RELEASE, longest, emptyList(), emptyList()))
        val facts = base.copy(
            header = FactsHeader(headerLongest, InputKind.JAR, headerLongest, sampleSha(), Long.MAX_VALUE),
        )

        assertEquals(facts, openFile(writeWith(facts, SectionEncoding.deflate(9))).loadAll().loaded())
    }

    @Test
    fun `a section that would deflate beyond the inflate ratio is stored as is and still loads`() {
        val repetitive = "a".repeat(FactsFormat.MAX_STRING_BYTES)
        val facts = factsWithTypes(ClassTypes(repetitive, 0, null, emptyList(), emptyList()))

        val file = writeWith(facts, SectionEncoding.deflate(9))

        assertEquals(facts, openFile(file).loadAll().loaded())
        assertEquals(STORED, sectionLocations(file).single { it.id == NAME_STRINGS }.codec)
        assertEquals(ZLIB, sectionLocations(file).single { it.id == NAMES }.codec)
    }

    @Test
    fun `the largest access flags and release round trip`() {
        val member = MemberFact(MemberKind.FIELD, FactsFormat.MAX_ACCESS_FLAGS, "f", "I")
        val facts = emptyFacts().copy(
            names = NamesSection(
                listOf(PackageFact("p", null)),
                listOf(ClassFact("p/C", "p", "C", null, FactsFormat.MAX_ACCESS_FLAGS, FactsFormat.MAX_RELEASE)),
            ),
            types = TypesSection(listOf(ClassTypes("p/C", FactsFormat.MAX_RELEASE, null, emptyList(), listOf(member)))),
        )

        assertEquals(facts, openFile(writeWith(facts, SectionEncoding.NONE)).loadAll().loaded())
    }

    @Test
    fun `every input kind, member kind, extension kind and extension source round trips`() {
        val holders = ExtensionKind.entries.flatMap { kind ->
            ExtensionSource.entries.map { source ->
                ExtensionFact("h/${kind.name}${source.name}", kind, source, "m", "1")
            }
        }
        val members = MemberKind.entries.map { kind -> MemberFact(kind, 1, kind.name, "()V") }

        for (inputKind in InputKind.entries) {
            val base = factsWithTypes(ClassTypes("p/C", 0, null, emptyList(), members.sortedBy { it.name }))
            val facts = base.copy(
                header = base.header.copy(inputKind = inputKind),
                extensions = ExtensionsSection(holders.sortedBy { it.holderClass }),
            )

            assertEquals(facts, openFile(writeWith(facts, SectionEncoding.NONE)).loadAll().loaded(), inputKind.name)
        }
    }

    @Test
    fun `the model refuses values the format cannot store`() {
        assertFailsWith<IllegalArgumentException> { ClassFact("p/C", "p", "C", null, FactsFormat.MAX_ACCESS_FLAGS + 1) }
        assertFailsWith<IllegalArgumentException> { ClassFact("p/C", "p", "C", null, -1) }
        assertFailsWith<IllegalArgumentException> { ClassFact("p/C", "p", "C", null, 1, FactsFormat.MAX_RELEASE + 1) }
        assertFailsWith<IllegalArgumentException> { MemberFact(MemberKind.FIELD, -1, "f", "I") }
        assertFailsWith<IllegalArgumentException> { ClassTypes("p/C", -1, null, emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { FactsHeader("p", InputKind.JAR, null, sampleSha(), -1L) }
        assertFailsWith<IllegalArgumentException> { SectionEncoding.deflate(0) }
        assertFailsWith<IllegalArgumentException> { SectionEncoding.deflate(10) }
    }

    @Test
    fun `the writer refuses strings above the limits and text that is not Unicode`() {
        val tooLong = "x".repeat(FactsFormat.MAX_STRING_BYTES + 1)
        val tooLongForHeader = "h".repeat(FactsFormat.MAX_HEADER_STRING_BYTES + 1)
        val base = factsWithTypes(ClassTypes("p/C", 0, null, emptyList(), emptyList()))
        val tooLongInTypes = ClassTypes("p/C", 0, tooLong, emptyList(), emptyList())

        val tooLongPackage = base.copy(
            names = NamesSection(listOf(PackageFact(tooLong, null)), emptyList()),
            types = TypesSection(emptyList()),
        )
        val refused = listOf(
            tooLongPackage,
            base.copy(types = TypesSection(listOf(tooLongInTypes))),
            base.copy(header = base.header.copy(producerVersion = tooLongForHeader)),
            base.copy(header = base.header.copy(coordinate = tooLongForHeader)),
        )

        for (facts in refused) {
            val failure = assertFailsWith<IllegalArgumentException> { FactsWriter.write(facts) }
            assertTrue("limit" in failure.message.orEmpty(), failure.message)
        }
        val surrogate = base.copy(
            names = NamesSection(listOf(PackageFact("\uD800", null)), emptyList()),
            types = TypesSection(emptyList()),
        )
        val failure = assertFailsWith<IllegalArgumentException> { FactsWriter.write(surrogate) }
        assertTrue("not valid Unicode" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the writer refuses a duplicate or an unlisted package, class, member or extension`() {
        val base = sampleFacts()
        val firstTypes = base.types.classes.first()
        val unlistedClass = ClassTypes("q/Unlisted", 0, null, emptyList(), emptyList())
        val refused = mapOf(
            "Duplicate package" to base.copy(
                names = base.names.copy(packages = base.names.packages + base.names.packages.first()),
            ),
            "Duplicate class" to base.copy(
                names = base.names.copy(classes = base.names.classes + base.names.classes.first()),
            ),
            "does not list" to base.copy(
                names = base.names.copy(classes = base.names.classes + ClassFact("q/C", "q", "C", null, 1)),
            ),
            "Duplicate member" to base.copy(
                types = TypesSection(
                    listOf(firstTypes.copy(members = firstTypes.members + firstTypes.members.first())) +
                        base.types.classes.drop(1),
                ),
            ),
            "Duplicate class and release" to base.copy(types = TypesSection(base.types.classes + firstTypes)),
            "not in the names section" to base.copy(types = TypesSection(base.types.classes + unlistedClass)),
            "Duplicate extension" to base.copy(
                extensions = ExtensionsSection(base.extensions.entries + base.extensions.entries.first()),
            ),
        )

        for ((expectedMessage, facts) in refused) {
            val failure = assertFailsWith<IllegalArgumentException>(expectedMessage) { FactsWriter.write(facts) }
            assertTrue(expectedMessage in failure.message.orEmpty(), "${failure.message} for $expectedMessage")
        }
    }

    @Test
    fun `sections written from different orders have the same raw bytes under both codecs`() {
        val facts = sampleFacts()

        for (encoding in listOf(SectionEncoding.NONE, SectionEncoding.deflate(6))) {
            val expected = writeWith(facts, encoding)
            for (seed in 1..20) {
                assertSameRawSections(expected, writeWith(shuffled(facts, Random(seed)), encoding))
            }
        }
    }

    @Test
    fun `files written without compression are identical for different insertion orders`() {
        val facts = sampleFacts()
        val expected = writeWith(facts, SectionEncoding.NONE)

        for (seed in 1..20) {
            val actual = writeWith(shuffled(facts, Random(seed)), SectionEncoding.NONE)
            assertContentEquals(expected, actual, "seed $seed")
        }
    }

    @Test
    fun `raw sections do not depend on the codec`() {
        val stored = writeWith(sampleFacts(), SectionEncoding.NONE)
        val deflated = writeWith(sampleFacts(), SectionEncoding.deflate(9))

        assertSameRawSections(stored, deflated)
        assertEquals(sectionLocations(stored).map { it.raw }, sectionLocations(deflated).map { it.raw })
    }

    private fun compareUtf8(left: String, right: String): Int =
        Arrays.compareUnsigned(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
