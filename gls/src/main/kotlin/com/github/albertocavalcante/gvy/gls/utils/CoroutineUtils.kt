package com.github.albertocavalcante.gvy.gls.utils

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

/**
 * Like [runCatching] but properly handles coroutine cancellation.
 *
 * Standard [runCatching] catches ALL exceptions including [CancellationException],
 * which breaks structured concurrency. This variant rethrows [CancellationException]
 * to preserve proper coroutine cancellation behavior.
 *
 * Usage:
 * ```kotlin
 * suspend fun doWork(): Result {
 *     return runSuspendCatching {
 *         // suspend operations that may fail
 *         computeResult()
 *     }.getOrElse { e ->
 *         logger.error(e) { "Operation failed" }
 *         defaultResult
 *     }
 * }
 * ```
 *
 * @param block The block to execute
 * @return [Result.success] with the result, or [Result.failure] with the exception
 * @throws CancellationException if the block was cancelled (not caught)
 */
internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
