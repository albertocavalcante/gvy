// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import java.nio.file.Path

/** Exact ordered subsequence of one immutable model, stored using the smaller set of included or excluded positions. */
internal class ReferenceSourceProjection private constructor(
    private val included: Boolean,
    private val positions: IntArray,
) {
    val retainedPositions: Int get() = positions.size

    fun sameAs(other: ReferenceSourceProjection?): Boolean =
        other != null && included == other.included && positions.contentEquals(other.positions)

    fun matches(model: List<Path>, actual: List<Path>): Boolean = when {
        included -> actual.size == positions.size && positions.indices.all { actual[it] == model[positions[it]] }
        positions.isEmpty() -> actual == model
        actual.size != model.size - positions.size -> false
        else -> matchesExcludingPositions(model, actual)
    }

    private fun matchesExcludingPositions(model: List<Path>, actual: List<Path>): Boolean {
        var skipped = 0
        var next = 0
        for (index in model.indices) {
            if (skipped < positions.size && positions[skipped] == index) {
                skipped++
            } else if (actual[next++] != model[index]) {
                return false
            }
        }
        return true
    }

    companion object {
        private val all = ReferenceSourceProjection(false, intArrayOf())
        private val none = ReferenceSourceProjection(true, intArrayOf())

        /** For duplicate paths, match the earliest available position. Reject any reordering or addition. */
        fun capture(model: List<Path>, selected: List<Path>): ReferenceSourceProjection? = when {
            selected.size > model.size -> null
            selected == model -> all
            selected.isEmpty() -> none
            else -> capturePositions(model, selected)
        }

        private fun capturePositions(model: List<Path>, selected: List<Path>): ReferenceSourceProjection? {
            val included = selected.size <= model.size - selected.size
            val positions = IntArray(if (included) selected.size else model.size - selected.size)
            var next = 0
            var recorded = 0
            for (index in model.indices) {
                val chosen = next < selected.size && selected[next] == model[index]
                if (chosen) next++
                if (chosen == included) {
                    if (recorded == positions.size) return null
                    positions[recorded++] = index
                }
            }
            return if (next == selected.size) ReferenceSourceProjection(included, positions) else null
        }
    }
}
