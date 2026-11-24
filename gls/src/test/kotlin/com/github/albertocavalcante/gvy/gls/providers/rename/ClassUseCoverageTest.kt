// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The completeness check accounts for every occurrence of the name or refuses; offsets here are made up. */
class ClassUseCoverageTest {
    private fun span(start: Int) = NameSpan(start, start + 7)

    private fun window(start: Int, end: Int, kind: TypeUseKind = TypeUseKind.PARAMETER) =
        NameWindow(start, end, kind, line = start / 100 + 1)

    private fun check(account: NameAccount) = ClassUseCoverage.check(account) { it / 100 + 1 }

    @Test
    fun `occurrences that are all located or narrowed from use windows are the edit, in source order`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(10), span(110), span(210)),
            located = listOf(span(210), span(10)),
            uses = listOf(window(105, 125), window(8, 20)),
        )

        assertEquals(ClassUses.Complete(listOf(span(10), span(110), span(210))), check(account))
    }

    @Test
    fun `an occurrence nothing accounts for refuses the rename with its line`() {
        val account =
            NameAccount("Service", listOf(span(10), span(310)), located = listOf(span(10)), uses = emptyList())

        assertEquals(
            ClassUses.Refused("Cannot rename 'Service': a use at line 4 could not be classified"),
            check(account),
        )
    }

    @Test
    fun `a use window that holds two occurrences outside its nested windows is refused, naming its kind`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(210), span(220), span(240)),
            located = emptyList(),
            uses = listOf(window(200, 260, TypeUseKind.SUPERCLASS), window(238, 250, TypeUseKind.TYPE_ARGUMENT)),
        )

        val reason = "Cannot rename 'Service': the superclass at line 3 cannot be located exactly in the source"
        assertEquals(ClassUses.Refused(reason), check(account))
    }

    @Test
    fun `a window takes the one occurrence its nested windows leave, as a type takes its own name around arguments`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(200), span(208)),
            located = emptyList(),
            uses = listOf(window(200, 216, TypeUseKind.FIELD), window(208, 215, TypeUseKind.TYPE_ARGUMENT)),
        )

        assertEquals(ClassUses.Complete(listOf(span(200), span(208))), check(account))
    }

    @Test
    fun `a window over another class's use takes the occurrence a nested use of the renamed class leaves`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(102), span(120)),
            located = listOf(span(120)),
            uses = listOf(window(118, 128, TypeUseKind.TYPE_ARGUMENT)),
            otherUses = listOf(window(100, 130)),
        )

        assertEquals(ClassUses.Complete(listOf(span(120))), check(account))
    }

    @Test
    fun `a window over another class's use that also holds an edit no window covers is refused`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(102), span(120)),
            located = listOf(span(120)),
            uses = emptyList(),
            otherUses = listOf(window(100, 130)),
        )

        val reason = "Cannot rename 'Service': the parameter type at line 2 cannot be located exactly in the source"
        assertEquals(ClassUses.Refused(reason), check(account))
    }

    @Test
    fun `an occurrence another class's smaller window takes is never taken by a larger use window`() {
        val account = NameAccount(
            "Date",
            occurrences = listOf(span(0), span(115)),
            located = listOf(span(0)),
            uses = listOf(window(100, 140, TypeUseKind.OUTER_QUALIFIER)),
            otherUses = listOf(window(105, 125, TypeUseKind.TYPE_ARGUMENT)),
        )

        assertEquals(ClassUses.Complete(listOf(span(0))), check(account))
    }

    @Test
    fun `an occurrence two equal windows give to different classes is refused`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(100)),
            located = emptyList(),
            uses = listOf(window(100, 110)),
            otherUses = listOf(window(100, 110)),
        )

        val reason = "Cannot rename 'Service': the name at line 2 is spelled by uses of two different classes"
        assertEquals(ClassUses.Refused(reason), check(account))
    }

    @Test
    fun `an edit that is not on an occurrence in code is refused`() {
        val account =
            NameAccount("Service", listOf(span(10)), located = listOf(span(10), span(50)), uses = emptyList())

        assertEquals(
            ClassUses.Refused("Cannot rename 'Service': the edit at line 1 is not on the name in code"),
            check(account),
        )
    }

    @Test
    fun `an occurrence that is both a use and another name is refused`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(110)),
            located = listOf(span(110)),
            uses = emptyList(),
            others = listOf(span(110)),
        )

        val reason = "Cannot rename 'Service': the name at line 2 is both a use of the class and another name"
        assertEquals(ClassUses.Refused(reason), check(account))
    }

    @Test
    fun `a name proven to be something else is left alone and a claim on no occurrence is ignored`() {
        val account = NameAccount(
            "Service",
            occurrences = listOf(span(10), span(110)),
            located = listOf(span(10)),
            uses = listOf(window(300, 320)),
            others = listOf(span(110), span(500)),
        )

        assertEquals(ClassUses.Complete(listOf(span(10))), check(account))
    }
}
