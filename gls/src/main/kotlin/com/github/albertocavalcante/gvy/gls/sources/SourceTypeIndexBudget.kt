// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

/** Unsupported syntax metadata is rejected as a whole; no ambiguity tier is truncated to fit. */
internal class SourceTypeIndexLimitException : RuntimeException("Source type index metadata exceeds its budget")

internal class SourceTypeIndexBudget {
    private var characters = 0L
    private var values = 0L

    fun name(value: String): String {
        charge(value)
        return value
    }

    fun candidates(tiers: List<List<String>>): List<List<String>> {
        tiers.forEach { tier -> tier.forEach(::charge) }
        return tiers
    }

    private fun charge(value: String) {
        if (
            value.length > MAX_NAME_CHARACTERS ||
            values >= MAX_VALUES ||
            value.length > MAX_CHARACTERS - characters
        ) {
            throw SourceTypeIndexLimitException()
        }
        values++
        characters += value.length
    }

    companion object {
        const val MAX_NAME_CHARACTERS = 4096
        const val MAX_CHARACTERS = 2L * 1024 * 1024
        const val MAX_VALUES = 65_536L
    }
}
