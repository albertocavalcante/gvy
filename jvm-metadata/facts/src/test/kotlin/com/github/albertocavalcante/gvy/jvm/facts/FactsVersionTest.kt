// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FactsVersionTest {
    @Test
    fun `a file with a newer minor version loads and reports that version`() {
        val bytes = writeWith(sampleFacts(), SectionEncoding.NONE).withByte(MINOR_OFFSET, FactsFormat.MINOR + 7)

        val file = openFile(bytes)

        assertEquals(FactsFormat.MINOR + 7, file.formatMinor)
        assertEquals(sampleFacts(), file.loadAll().loaded())
    }

    @Test
    fun `a section with an unknown id is skipped even when its row and bytes are nonsense`() {
        val unknown = TestSection(id = 200, codec = 99, stored = byteArrayOf(1, 2, 3), rawLength = 1_000_000, crc = 0)
        val bytes = buildFile(sections() + unknown, minor = 3)

        val file = openFile(bytes)

        assertEquals(3, file.formatMinor)
        file.loadAll().loaded()
    }

    @Test
    fun `a section with an unknown id between known sections does not move the others`() {
        val unknown = plain(id = 77, raw = ByteArray(40) { 9 })
        val known = sections()
        val bytes = buildFile(listOf(known[0], unknown) + known.drop(1))

        openFile(bytes).loadAll().loaded()
    }

    @Test
    fun `an unknown section id that appears twice is corrupt like any other repeated id`() {
        val unknown = plain(id = 77, raw = ByteArray(4))

        FactsFile.open(buildFile(sections() + unknown + unknown)).assertRejected(RejectReason.CORRUPT)
        openFile(buildFile(sections() + unknown)).loadAll().loaded()
    }

    @Test
    fun `a different major version is rejected`() {
        val bytes = writeWith(sampleFacts(), SectionEncoding.NONE)

        for (major in listOf(0, FactsFormat.MAJOR + 1, 255)) {
            FactsFile.open(bytes.withByte(MAJOR_OFFSET, major)).assertRejected(RejectReason.UNSUPPORTED_VERSION)
        }
        FactsFile.open(bytes.withByte(MAJOR_OFFSET + 1, 1)).assertRejected(RejectReason.UNSUPPORTED_VERSION)
    }

    private companion object {
        const val MAJOR_OFFSET = 4
        const val MINOR_OFFSET = 6
    }
}
