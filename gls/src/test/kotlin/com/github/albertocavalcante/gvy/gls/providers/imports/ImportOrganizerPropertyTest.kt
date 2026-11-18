// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide

/** Properties of [ImportOrganizer] over generated import blocks, in every line-ending style. */
internal class ImportOrganizerPropertyTest {

    data class Case(val text: String, val unused: List<UnusedImport>)

    private val statements = listOf(
        "import a.Alpha",
        "import a.Alpha as AL",
        "import b.c.Beta",
        "import java.util.List",
        "import java.util.*",
        "import javax.swing.JFrame",
        "import java.io.File as F",
        "import static a.Util.helper",
        "import static a.Util.*",
        "import static java.lang.Math.max;",
        "import b.c.Beta // keep me",
        "// why\nimport a.Gamma",
    )

    @Provide
    fun cases(): Arbitrary<Case> {
        val picks = Arbitraries.integers().between(0, statements.lastIndex).list().ofMinSize(1).ofMaxSize(MAX_IMPORTS)
        val newlines = Arbitraries.of("\n", "\r\n", "\r")
        val unusedFlags = Arbitraries.of(true, false).list().ofSize(MAX_LINES)
        return Combinators.combine(picks, newlines, unusedFlags).`as` { picked, newline, flags ->
            val lines = picked.flatMap { statements[it].split("\n") }
            val text = lines.joinToString(newline) + newline + newline + "class Body {}" + newline
            val unused = lines.indices.filter { flags[it] }.mapNotNull { line ->
                ImportLineParser.parse(lines[line])?.let { UnusedImport(it.target, line) }
            }
            Case(text, unused)
        }
    }

    private fun apply(text: String, edits: List<TextReplacement>): String {
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.newText + result.substring(edit.end)
        }
        return result
    }

    private fun targetsOf(text: String): List<ImportTarget> =
        text.split("\r\n", "\n", "\r").mapNotNull { ImportLineParser.parse(it)?.target }

    @Property(tries = 300)
    fun `organizing twice yields no further edits`(@ForAll("cases") case: Case): Boolean {
        val once = apply(case.text, ImportOrganizer.organize(case.text, case.unused))

        return ImportOrganizer.organize(once, emptyList()).isEmpty()
    }

    @Property(tries = 300)
    fun `organizing never drops an import the detector did not flag`(@ForAll("cases") case: Case): Boolean {
        val edits = ImportOrganizer.organize(case.text, case.unused)
        val before = targetsOf(case.text)
        val after = targetsOf(apply(case.text, edits)).toSet()
        val flagged = case.unused.map { it.target }.toSet()

        val neverInvents = after.all { it in before }
        val keepsUnflagged = before.filter { it !in flagged }.all { it in after }
        return neverInvents && keepsUnflagged
    }

    @Property(tries = 300)
    fun `edits are ordered, disjoint and inside the text`(@ForAll("cases") case: Case): Boolean {
        val edits = ImportOrganizer.organize(case.text, case.unused)

        val inside = edits.all { it.start in 0..it.end && it.end <= case.text.length }
        return inside && edits.zipWithNext().all { (a, b) -> a.end <= b.start }
    }

    private companion object {
        const val MAX_IMPORTS = 12

        /** Every import is at most two lines (a comment and the statement). */
        const val MAX_LINES = MAX_IMPORTS * 2
    }
}
