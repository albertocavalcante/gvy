package com.github.albertocavalcante.groovylsp.utils

import kotlinx.coroutines.CancellationException

/**
 * Rethrows the throwable if it's a [CancellationException] or [Error].
 *
 * Use this in `runCatching` blocks within coroutines to ensure that
 * cancellation and critical errors are not swallowed.
 *
 * @param throwable The throwable to check
 * @throws CancellationException if the throwable is a CancellationException
 * @throws Error if the throwable is an Error
 */
internal fun rethrowIfCancellationOrError(throwable: Throwable) {
    when (throwable) {
        is CancellationException -> throw throwable
        is Error -> throw throwable
    }
}
