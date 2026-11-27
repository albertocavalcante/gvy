// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The decoded-item budget: every pooled string, package, class, interface, member, parameter name, extension entry
 * and types entry costs one item, charged before the objects for it are allocated, from one budget per [FactsFile].
 */
class FactsDecodedBudgetTest {
    private val abcPool = poolOf("A", "B", "C")
    private val noPackages = namesSection(packages = emptyList(), classes = emptyList())
    private val one = typesSection(TypesRow(members = emptyList()))

    @Test
    fun `the default budget leaves room for a full JDK image and stays far below a heap`() {
        // About 40 thousand classes, counted in names and in types, 60 thousand interface references, 500 thousand
        // members, 250 thousand pooled strings and 100 thousand parameter names add up to about 1 million items.
        val fullJdkItems = 2 * 40_000 + 60_000 + 500_000 + 250_000 + 100_000
        val heapBytesPerItem = 108L

        assertTrue(ReadLimits().maxDecodedItems >= 3 * fullJdkItems)
        assertTrue(ReadLimits().maxDecodedItems * heapBytesPerItem < 512L * 1024 * 1024)
    }

    @Test
    fun `a file whose items equal the budget loads and one item fewer is oversized`() {
        val facts = sampleFacts()
        val bytes = writeWith(facts, SectionEncoding.NONE)
        val total = namesItems(facts, bytes) + extensionItems(facts) + typesItems(facts, bytes)

        openFile(bytes, ReadLimits(maxDecodedItems = total)).loadAll().loaded()
        openFile(bytes, ReadLimits(maxDecodedItems = total - 1)).loadAll().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `the budget is shared across sections and across separate calls on one file`() {
        val facts = sampleFacts()
        val bytes = writeWith(facts, SectionEncoding.NONE)
        val total = namesItems(facts, bytes) + typesItems(facts, bytes) + extensionItems(facts)

        val file = openFile(bytes, ReadLimits(maxDecodedItems = total - 1))

        file.names().loaded()
        file.types().loaded()
        file.extensions().assertRejected(RejectReason.OVERSIZED)
        file.names().loaded()
        file.types().loaded()
        file.extensions().assertRejected(RejectReason.OVERSIZED)
        file.loadAll().assertRejected(RejectReason.OVERSIZED)
        val exact = openFile(bytes, ReadLimits(maxDecodedItems = total))
        exact.names().loaded()
        exact.types().loaded()
        exact.extensions().loaded()
        exact.names().loaded()
        exact.loadAll().loaded()
    }

    @Test
    fun `a call gives the same result whichever calls came before it`() {
        val facts = sampleFacts()
        val bytes = writeWith(facts, SectionEncoding.NONE)
        val total = namesItems(facts, bytes) + typesItems(facts, bytes) + extensionItems(facts)
        val limits = ReadLimits(maxDecodedItems = total - 1)

        val typesFirst = openFile(bytes, limits)
        typesFirst.types().loaded()
        typesFirst.extensions().assertRejected(RejectReason.OVERSIZED)
        val extensionsFirst = openFile(bytes, limits)
        extensionsFirst.extensions().assertRejected(RejectReason.OVERSIZED)
        extensionsFirst.types().loaded()
        extensionsFirst.names().loaded()
        openFile(bytes, limits).names().loaded()
    }

    @Test
    fun `an extensions section that overspends the budget does not stop types`() {
        val facts = sampleFacts()
        val bytes = writeWith(facts, SectionEncoding.NONE)
        val withoutExtensions = namesItems(facts, bytes) + typesItems(facts, bytes)

        val file = openFile(bytes, ReadLimits(maxDecodedItems = withoutExtensions))

        assertEquals(facts.types, file.types().loaded())
        file.extensions().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `names charge neither the type pool, the types nor the extensions and types not the extensions`() {
        val facts = sampleFacts()
        val bytes = writeWith(facts, SectionEncoding.NONE)
        val namesOnly = namesItems(facts, bytes)
        val typesToo = namesOnly + typesItems(facts, bytes)

        openFile(bytes, ReadLimits(maxDecodedItems = namesOnly)).names().loaded()
        openFile(bytes, ReadLimits(maxDecodedItems = namesOnly - 1)).names().assertRejected(RejectReason.OVERSIZED)
        openFile(bytes, ReadLimits(maxDecodedItems = typesToo)).types().loaded()
        openFile(bytes, ReadLimits(maxDecodedItems = typesToo - 1)).types().assertRejected(RejectReason.OVERSIZED)
    }

    @Test
    fun `the name pool is charged one item per string`() {
        assertBudget(total = 4, sections(nameStrings = poolOf("A", "B", "C", "D"), names = noPackages)) { names() }
    }

    @Test
    fun `packages are charged one item each`() {
        assertBudget(total = 6, sections(nameStrings = abcPool, names = namesSection(listOf(0, 1, 2), emptyList()))) {
            names()
        }
    }

    @Test
    fun `classes are charged one item each`() {
        val classes = namesSection(listOf(0), listOf(ClassRow(0), ClassRow(1), ClassRow(2)))

        assertBudget(total = 7, sections(nameStrings = abcPool, names = classes)) { names() }
    }

    @Test
    fun `extension entries are charged one item each`() {
        val entries = extensionsSection(ExtensionRow(holder = 0), ExtensionRow(holder = 1), ExtensionRow(holder = 2))

        // Extensions are decoded last: the pool (3), the names (0), the type pool (1) and the types (0) come first.
        assertBudget(total = 7, sections(nameStrings = abcPool, names = noPackages, extensions = entries)) {
            extensions()
        }
    }

    @Test
    fun `the type pool is charged one item per string`() {
        // Types also need the name pool (1), the packages and classes (2) and the type pool (4).
        assertBudget(total = 7, sections(typeStrings = poolOf("A", "B", "C", "D"))) { types() }
    }

    @Test
    fun `entries of the types section are charged one item each`() {
        assertBudget(total = 5, sections(types = one)) { types() }
    }

    @Test
    fun `an interface of a class is charged one item, the worst shape of an entry with a single reference`() {
        val withInterface = typesSection(TypesRow(interfaces = listOf(0), members = emptyList()))

        assertBudget(total = 6, sections(types = withInterface)) { types() }
    }

    @Test
    fun `a member is charged one item`() {
        assertBudget(total = 6, sections(types = typesSection(TypesRow(members = listOf(memberBytes()))))) { types() }
    }

    @Test
    fun `a parameter name is charged one item`() {
        val member = memberBytes(MemberRow(parameterNames = listOf(0)))

        assertBudget(total = 7, sections(types = typesSection(TypesRow(members = listOf(member))))) { types() }
    }

    @Test
    fun `an item count that the bytes could not hold is corrupt, not oversized, whatever the budget`() {
        val tooMany = bytes { varint(Int.MAX_VALUE) }
        val file = openFile(buildFile(sections(names = tooMany)), ReadLimits(maxDecodedItems = 1))

        // The name pool costs one item, so the budget is spent when the names section is reached.
        file.names().assertRejected(RejectReason.CORRUPT)
    }

    /** Runs [call] with a budget of [total], which must load, and of one item fewer, which must be oversized. */
    private fun assertBudget(total: Int, file: List<TestSection>, call: FactsFile.() -> LoadResult<*>) {
        val bytes = buildFile(file)

        openFile(bytes, ReadLimits(maxDecodedItems = total)).call().loaded()
        openFile(bytes, ReadLimits(maxDecodedItems = total - 1)).call().assertRejected(RejectReason.OVERSIZED)
    }
}
