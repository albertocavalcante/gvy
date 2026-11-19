// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

/** Release every owner in order, then report the first failure with later failures suppressed. */
@Suppress("TooGenericExceptionCaught") // Cleanup must continue even after a fatal failure, which is rethrown below.
internal fun shutdownResources(vararg steps: () -> Unit) {
    var firstFailure: Throwable? = null
    for (step in steps) {
        try {
            step()
        } catch (failure: Throwable) {
            val previous = firstFailure
            if (previous == null) {
                firstFailure = failure
            } else if (previous !== failure) {
                previous.addSuppressed(failure)
            }
        }
    }
    firstFailure?.let { throw it }
}
