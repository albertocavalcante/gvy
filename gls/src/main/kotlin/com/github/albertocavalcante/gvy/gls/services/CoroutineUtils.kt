package com.github.albertocavalcante.gvy.gls.services

import kotlinx.coroutines.CancellationException

/**
 * Rethrows CancellationException and Error types to ensure they propagate correctly.
 *
 * This function is used in exception handlers to ensure that special exception types
 * (CancellationException for cooperative cancellation and Error for fatal errors)
 * are not swallowed by generic catch blocks.
 *
 * @param throwable The throwable to check and potentially rethrow.
 * @throws CancellationException if the throwable is a CancellationException.
 * @throws Error if the throwable is an Error.
 */
internal fun rethrowIfCancellationOrError(throwable: Throwable) {
    when (throwable) {
        is CancellationException -> throw throwable
        is Error -> throw throwable
    }
}
