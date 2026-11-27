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
            strings = SectionEncoding.NONE,
            names = SectionEncoding.deflate(9),
            types = SectionEncoding.deflate(1),
            extensions = SectionEncoding.NONE,
        )

        val file = openFile(FactsWriter.write(facts, options))

        assertEquals(facts, file.loadAll().loaded())
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
    fun `reported byte counts add up to the file`() {
        val bytes = writeWith(sampleFacts(), SectionEncoding.deflate(6))

        val opened = FactsFile.open(bytes)
        val file = opened.loaded()
        val headerBytes = opened.bytesRead()
        val sectionBytes = file.loadAll().bytesRead()

        assertEquals(bytes.size, headerBytes + sectionBytes)
        val namesBytes = file.names().bytesRead()
        val typesBytes = file.types().bytesRead()
        assertTrue(namesBytes < sectionBytes && typesBytes < sectionBytes)
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
        val facts = emptyFacts().copy(
            names = NamesSection(
                packages = strings.map { PackageFact(it, "m-$it") },
                classes = emptyList(),
            ),
            types = TypesSection(
                listOf(ClassTypes("p/${strings[1]}", 0, strings[3], strings, emptyList())),
            ),
        )

        val loaded = openFile(writeWith(facts, SectionEncoding.deflate(6))).loadAll().loaded()

        assertEquals(facts.names.packages.toSet(), loaded.names.packages.toSet())
        assertEquals(facts.types, loaded.types)
    }

    @Test
    fun `the string pool is sorted by UTF-8 bytes and not by UTF-16 units`() {
        // U+FF5E is EF BD 9E and U+1F600 is F0 9F 98 80, so the byte order puts the emoji last.
        // In UTF-16 the emoji starts with the surrogate D83D, which sorts before U+FF5E.
        val facts = emptyFacts().copy(
            names = NamesSection(listOf(PackageFact("～", null), PackageFact("😀", null)), emptyList()),
        )

        val pool = readPool(writeWith(facts, SectionEncoding.NONE))

        assertEquals(listOf("～", "😀"), pool)
        assertEquals(pool.sortedWith { left, right -> compareUtf8(left, right) }, pool)
    }

    @Test
    fun `strings and header strings of exactly the maximum length round trip`() {
        // Seeded letters, not one repeated character: a repeated character deflates beyond the inflate ratio cap.
        val random = Random(7)
        val letters = CharArray(FactsFormat.MAX_STRING_BYTES) { random.nextInt('a'.code, 'z'.code + 1).toChar() }
        val longest = String(letters)
        val headerLongest = "h".repeat(FactsFormat.MAX_HEADER_STRING_BYTES)
        val facts = emptyFacts().copy(
            header = FactsHeader(headerLongest, InputKind.JAR, headerLongest, sampleSha(), Long.MAX_VALUE),
            types = TypesSection(listOf(ClassTypes(longest, FactsFormat.MAX_RELEASE, null, emptyList(), emptyList()))),
        )

        assertEquals(facts, openFile(writeWith(facts, SectionEncoding.deflate(9))).loadAll().loaded())
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
        val base = emptyFacts()

        val tooLongClass = ClassTypes(tooLong, 0, null, emptyList(), emptyList())

        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(types = TypesSection(listOf(tooLongClass))))
        }
        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(header = base.header.copy(producerVersion = tooLongForHeader)))
        }
        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(header = base.header.copy(coordinate = tooLongForHeader)))
        }
        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(names = NamesSection(listOf(PackageFact("\uD800", null)), emptyList())))
        }
    }

    @Test
    fun `the writer refuses a duplicate package, class or member and an unlisted package`() {
        val base = sampleFacts()
        val duplicatePackage = base.names.packages + base.names.packages.first()
        val duplicateClass = base.names.classes + base.names.classes.first()
        val unlistedPackage = base.names.classes + ClassFact("q/C", "q", "C", null, 1)
        val firstTypes = base.types.classes.first()
        val duplicateMember = firstTypes.copy(members = firstTypes.members + firstTypes.members.first())

        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(names = base.names.copy(packages = duplicatePackage)))
        }
        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(names = base.names.copy(classes = duplicateClass)))
        }
        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(names = base.names.copy(classes = unlistedPackage)))
        }
        assertFailsWith<IllegalArgumentException> {
            FactsWriter.write(base.copy(types = TypesSection(listOf(duplicateMember) + base.types.classes.drop(1))))
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

    @Test
    fun `every input kind, member kind, extension kind and extension source round trips`() {
        val sources = ExtensionSource.entries
        val holders = ExtensionKind.entries.flatMap { kind ->
            sources.map { source -> ExtensionFact("h/${kind.name}${source.name}", kind, source, "m", "1") }
        }
        val members = MemberKind.entries.map { kind -> MemberFact(kind, 1, kind.name, "()V") }

        for (inputKind in InputKind.entries) {
            val facts = emptyFacts().copy(
                header = emptyFacts().header.copy(inputKind = inputKind),
                types = TypesSection(listOf(ClassTypes("p/C", 0, null, emptyList(), members.sortedBy { it.name }))),
                extensions = ExtensionsSection(holders.sortedBy { it.holderClass }),
            )

            assertEquals(facts, openFile(writeWith(facts, SectionEncoding.NONE)).loadAll().loaded(), inputKind.name)
        }
    }

    private fun compareUtf8(left: String, right: String): Int =
        Arrays.compareUnsigned(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
