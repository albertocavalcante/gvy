// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LibraryInputSnapshotTest {
    @Test
    fun `captured bytes cannot change through input or returned arrays`() {
        val bytes = "def call() { 'first' }".toByteArray()
        val entries = mutableMapOf("vars/kitchen.groovy" to bytes)
        val snapshot = LibrarySourceSnapshot(entries)
        val fingerprint = snapshot.fingerprint

        bytes.fill(0)
        entries.clear()
        val firstRead = requireNotNull(snapshot.read("vars/kitchen.groovy"))
        assertThat(firstRead.decodeToString()).contains("first")
        firstRead.fill(0)

        assertThat(requireNotNull(snapshot.read("vars/kitchen.groovy")).decodeToString()).contains("first")
        assertThat(snapshot.fingerprint).isEqualTo(fingerprint)
        assertThat(snapshot.read("missing")).isNull()
    }

    @Test
    fun `entry ordering is irrelevant but paths and actual bytes identify content`() {
        val first = LibrarySourceSnapshot(
            linkedMapOf(
                "src/A.groovy" to byteArrayOf(1),
                "vars/a.groovy" to byteArrayOf(2),
            ),
        )
        val reordered = LibrarySourceSnapshot(
            linkedMapOf(
                "vars/a.groovy" to byteArrayOf(2),
                "src/A.groovy" to byteArrayOf(1),
            ),
        )
        val edited = LibrarySourceSnapshot(mapOf("src/A.groovy" to byteArrayOf(3), "vars/a.groovy" to byteArrayOf(2)))
        val renamed = LibrarySourceSnapshot(mapOf("src/B.groovy" to byteArrayOf(1), "vars/a.groovy" to byteArrayOf(2)))

        assertThat(first.fingerprint).isEqualTo(reordered.fingerprint).matches("[a-f0-9]{64}")
        assertThat(first.fingerprint).isNotEqualTo(edited.fingerprint).isNotEqualTo(renamed.fingerprint)
    }

    @Test
    fun `fingerprints delimit paths and arbitrary bytes rather than concatenating them`() {
        val first = LibrarySourceSnapshot(mapOf("a" to "bc".toByteArray()))
        val second = LibrarySourceSnapshot(mapOf("ab" to "c".toByteArray()))
        val unicode = LibrarySourceSnapshot(mapOf("vars/café.groovy" to byteArrayOf(0, -1, 10)))

        assertThat(first.fingerprint).isNotEqualTo(second.fingerprint)
        assertThat(unicode.fingerprint).matches("[a-f0-9]{64}")
        assertThat(LibrarySourceSnapshot(emptyMap()).fingerprint).isNotEqualTo(first.fingerprint)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "/absolute", "../escape", "vars/../escape", "vars//a", "./a", "a\\b", "a/", "C:/absolute", "C:relative",
        ],
    )
    fun `source entry identifiers must be normalized relative paths`(path: String) {
        assertThrows<IllegalArgumentException> { LibrarySourceSnapshot(mapOf(path to byteArrayOf(1))) }
    }

    @Test
    fun `source entry identifiers cannot contain NUL`() {
        assertThrows<IllegalArgumentException> { LibrarySourceSnapshot(mapOf("a\u0000b" to byteArrayOf(1))) }
    }

    @Test
    fun `distinct unpaired surrogates are not replaced with the same encoded string`() {
        val first = LibrarySourceSnapshot(mapOf("\uD800" to byteArrayOf(1)))
        val second = LibrarySourceSnapshot(mapOf("\uD801" to byteArrayOf(1)))
        assertThat(first.fingerprint).isNotEqualTo(second.fingerprint)
    }

    @Test
    fun `resolved library identity requires alias and resolver provenance`() {
        val identity = input("kitchen-lib", 1)
        assertThrows<IllegalArgumentException> { identity.copy(alias = " ") }
        assertThrows<IllegalArgumentException> { identity.copy(resolverIdentity = "") }
        assertThrows<IllegalArgumentException> { identity.copy(contentFingerprint = "") }
    }

    @Test
    fun `selected libraries and dependencies retain order and duplicates without mutable aliases`() {
        val first = input("first", 1)
        val second = input("second", 2)
        val libraries = mutableListOf(first, second)
        val dependencies = mutableListOf(second, first, first)
        val snapshot = LibraryInputSnapshot(libraries, dependencies, LibraryClosureStatus.UNKNOWN)
        libraries.clear()
        dependencies.reverse()

        assertThat(snapshot.libraries).containsExactly(first, second)
        assertThat(snapshot.dependencies).containsExactly(second, first, first)
        assertThrows<UnsupportedOperationException> { (snapshot.libraries as MutableList).clear() }
        assertThrows<UnsupportedOperationException> { (snapshot.dependencies as MutableList).clear() }
    }

    @Test
    fun `input identity includes order provenance revision bytes and closure uncertainty`() {
        val first = input("kitchen-lib", 1)
        val dependency = input("helper", 2)
        val baseline = inputs(listOf(first), listOf(dependency))
        val alternatives = listOf(
            inputs(listOf(first.copy(alias = "other")), listOf(dependency)),
            inputs(listOf(first.copy(resolverIdentity = "folder")), listOf(dependency)),
            inputs(listOf(first.copy(selectedRevision = "release")), listOf(dependency)),
            inputs(listOf(input("kitchen-lib", 3)), listOf(dependency)),
            inputs(listOf(first), emptyList()),
            inputs(listOf(first), listOf(dependency, dependency)),
            inputs(listOf(first), listOf(dependency), LibraryClosureStatus.COMPLETE),
            inputs(listOf(first), listOf(dependency), LibraryClosureStatus.PARTIAL),
        )

        assertThat(baseline.fingerprint).matches("[a-f0-9]{64}")
        alternatives.forEach { assertThat(it.fingerprint).isNotEqualTo(baseline.fingerprint) }
        assertThat(inputs(listOf(first, dependency)).fingerprint)
            .isNotEqualTo(inputs(listOf(dependency, first)).fingerprint)
        assertThat(inputs(listOf(first), listOf(first, dependency)).fingerprint)
            .isNotEqualTo(inputs(listOf(first), listOf(dependency, first)).fingerprint)
        assertThat(first.copy(selectedRevision = null)).isNotEqualTo(first.copy(selectedRevision = ""))
        assertThat(inputs(listOf(first.copy(selectedRevision = null))).fingerprint)
            .isNotEqualTo(inputs(listOf(first.copy(selectedRevision = ""))).fingerprint)
    }

    private fun input(alias: String, content: Byte) = LibraryInputIdentity(
        alias,
        "manual-profile",
        "main",
        LibrarySourceSnapshot(mapOf("vars/step.groovy" to byteArrayOf(content))).fingerprint,
    )

    private fun inputs(
        libraries: List<LibraryInputIdentity>,
        dependencies: List<LibraryInputIdentity> = emptyList(),
        status: LibraryClosureStatus = LibraryClosureStatus.UNKNOWN,
    ) = LibraryInputSnapshot(libraries, dependencies, status)
}
