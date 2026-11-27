// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts

import org.junit.jupiter.api.Test

/**
 * The reader requires the keys of every table to be strictly increasing, so a file has one encoding and a key is
 * unique. Each test crafts an ascending control that loads and descending or repeated keys that do not.
 */
class FactsSectionOrderTest {
    private val abPool = poolOf("A", "B")

    @Test
    fun `package names must be strictly increasing`() {
        val loads = namesSection(packages = listOf(0, 1))
        val repeated = namesSection(packages = listOf(0, 0))
        val descending = namesSection(packages = listOf(1, 0))

        openNames(loads).names().loaded()
        openNames(repeated).names().assertRejected(RejectReason.CORRUPT)
        openNames(descending).names().assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `classes must be strictly increasing by internal name and then release`() {
        val loads = namesSection(classes = listOf(ClassRow(internalName = 0, release = 0), ClassRow(internalName = 1)))
        val sameNameLaterRelease = namesSection(classes = listOf(ClassRow(release = 0), ClassRow(release = 1)))
        val repeated = namesSection(classes = listOf(ClassRow(), ClassRow()))
        val descendingName = namesSection(classes = listOf(ClassRow(internalName = 1), ClassRow(internalName = 0)))
        val descendingRelease = namesSection(classes = listOf(ClassRow(release = 1), ClassRow(release = 0)))

        openNames(loads).names().loaded()
        openNames(sameNameLaterRelease).names().loaded()
        for (broken in listOf(repeated, descendingName, descendingRelease)) {
            openNames(broken).names().assertRejected(RejectReason.CORRUPT)
        }
    }

    @Test
    fun `classes of the types section must be strictly increasing by class index`() {
        val names = namesSection(classes = listOf(ClassRow(internalName = 0), ClassRow(internalName = 1)))
        val loads = typesSection(TypesRow(classIndex = 0), TypesRow(classIndex = 1))
        val repeated = typesSection(TypesRow(classIndex = 1), TypesRow(classIndex = 1))
        val descending = typesSection(TypesRow(classIndex = 1), TypesRow(classIndex = 0))

        fun typesOf(section: ByteArray) =
            openFile(buildFile(sections(nameStrings = abPool, names = names, types = section))).types()

        typesOf(loads).loaded()
        typesOf(repeated).assertRejected(RejectReason.CORRUPT)
        typesOf(descending).assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `members must be strictly increasing by name and then descriptor`() {
        fun member(name: Int, descriptor: Int) = memberBytes(MemberRow(name = name, descriptor = descriptor))
        fun typesOf(vararg members: ByteArray) = openFile(
            buildFile(sections(typeStrings = abPool, types = typesSection(TypesRow(members = members.toList())))),
        ).types()

        typesOf(member(0, 0), member(0, 1), member(1, 0)).loaded()
        typesOf(member(0, 0), member(0, 0)).assertRejected(RejectReason.CORRUPT)
        typesOf(member(1, 0), member(0, 0)).assertRejected(RejectReason.CORRUPT)
        typesOf(member(0, 1), member(0, 0)).assertRejected(RejectReason.CORRUPT)
    }

    @Test
    fun `extensions must be strictly increasing by every field in the order they are written`() {
        fun extensionsOf(vararg rows: ExtensionRow) = openFile(
            buildFile(sections(nameStrings = abPool, extensions = extensionsSection(*rows))),
        ).extensions()
        val base = ExtensionRow(holder = 0, kind = 0, source = 0, moduleName = 0, moduleVersion = 0)

        extensionsOf(base, ExtensionRow(0, 0, 0, 0, 1), ExtensionRow(0, 0, 0, 1, 0)).loaded()
        extensionsOf(
            base,
            ExtensionRow(0, 0, 1, 0, 0),
            ExtensionRow(0, 1, 0, 0, 0),
            ExtensionRow(1, 0, 0, 0, 0),
        ).loaded()
        extensionsOf(base, base).assertRejected(RejectReason.CORRUPT)
        extensionsOf(ExtensionRow(1, 0, 0, 0, 0), base).assertRejected(RejectReason.CORRUPT)
        extensionsOf(ExtensionRow(0, 1, 0, 0, 0), base).assertRejected(RejectReason.CORRUPT)
        extensionsOf(ExtensionRow(0, 0, 1, 0, 0), base).assertRejected(RejectReason.CORRUPT)
        extensionsOf(ExtensionRow(0, 0, 0, 1, 0), base).assertRejected(RejectReason.CORRUPT)
        extensionsOf(ExtensionRow(0, 0, 0, 0, 1), base).assertRejected(RejectReason.CORRUPT)
    }

    private fun openNames(names: ByteArray): FactsFile =
        openFile(buildFile(sections(nameStrings = abPool, names = names)))
}
