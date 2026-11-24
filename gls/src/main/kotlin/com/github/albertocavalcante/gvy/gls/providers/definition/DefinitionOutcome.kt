// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition

/**
 * What a definition request found. A failed lookup is its own case, never an empty answer: "nothing is there"
 * ([NotFound]) and "the lookup broke" ([Failed]) are different things to tell the client.
 */
sealed interface DefinitionOutcome<out T> {
    /** One or more targets. */
    data class Found<T>(val targets: List<T>) : DefinitionOutcome<T>

    /** A benign miss: no symbol under the cursor, no definition with a position, or the file is not compiled. */
    data object NotFound : DefinitionOutcome<Nothing>

    /** The lookup could not be carried out; [error] is what went wrong. */
    data class Failed(val error: Throwable) : DefinitionOutcome<Nothing>

    /** The targets, empty for [NotFound]; a [Failed] outcome throws its [Failed.error] for the response boundary. */
    fun targetsOrThrow(): List<T> = when (this) {
        is Found -> targets
        is NotFound -> emptyList()
        is Failed -> throw error
    }
}
