// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A decoded value is kept and handed to every caller, so no caller may be able to change it for another. */
class FactsDecodedListsTest {
    private val facts = sampleFacts()
    private val file = openFile(writeWith(facts, SectionEncoding.NONE))

    @Test
    fun `the lists of the names section cannot be modified through a cast`() {
        val names = file.names().loaded()

        assertFailsWith<UnsupportedOperationException> { mutable(names.packages).clear() }
        assertFailsWith<UnsupportedOperationException> { mutable(names.classes).clear() }
        assertEquals(facts.names, file.names().loaded())
    }

    @Test
    fun `the lists of the types section cannot be modified through a cast`() {
        val types = file.types().loaded()
        val outer = types.classes.first { it.interfaces.isNotEmpty() }
        val member = outer.members.first { it.parameterNames?.isNotEmpty() == true }

        assertFailsWith<UnsupportedOperationException> { mutable(types.classes).clear() }
        assertFailsWith<UnsupportedOperationException> { mutable(outer.interfaces).clear() }
        assertFailsWith<UnsupportedOperationException> { mutable(outer.members).clear() }
        assertFailsWith<UnsupportedOperationException> { mutable(checkNotNull(member.parameterNames)).clear() }
        assertEquals(facts.types, file.types().loaded())
    }

    @Test
    fun `the list of the extensions section cannot be modified through a cast`() {
        val extensions = file.extensions().loaded()

        assertFailsWith<UnsupportedOperationException> { mutable(extensions.entries).clear() }
        assertEquals(facts.extensions, file.extensions().loaded())
    }

    @Test
    fun `a second caller of loadAll sees the whole model unchanged after an attempt to modify it`() {
        val loaded = file.loadAll().loaded()

        assertFailsWith<UnsupportedOperationException> { mutable(loaded.names.classes).clear() }
        assertEquals(facts, file.loadAll().loaded())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> mutable(list: List<T>): MutableList<T> = list as MutableList<T>
}
