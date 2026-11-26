// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.utils.runSuspendCatching
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import java.util.Spliterator
import java.util.stream.BaseStream
import kotlin.coroutines.CoroutineContext

/** Source and parse components captured from one cache entry. Consumers treat the AST as read-only. */
internal data class CompiledDocumentSnapshot(
    val sourceText: String,
    val parseResult: ParseResult,
    val referenceProducer: ReferenceProducer? = null,
    val sourceIdentity: String? = null,
)

/** Result of acquiring source and parser facts under one ownership fence. */
internal sealed interface SnapshotAcquisition {
    sealed class Available(val snapshot: CompiledDocumentSnapshot, val isCurrent: () -> Boolean) : SnapshotAcquisition {
        suspend fun <T> query(compute: suspend (CompiledDocumentSnapshot) -> T): SnapshotQuery<T> =
            queryCurrentSnapshot(isCurrent, partial = this is Partial) { compute(snapshot) }
    }

    class Ready(snapshot: CompiledDocumentSnapshot, isCurrent: () -> Boolean) : Available(snapshot, isCurrent)
    class Partial(snapshot: CompiledDocumentSnapshot, isCurrent: () -> Boolean) : Available(snapshot, isCurrent)
    data object Missing : SnapshotAcquisition
    data object Stale : SnapshotAcquisition
    class Failure(val cause: Exception) : SnapshotAcquisition
}

/** A query's answer is returned only if its captured inputs still own it. */
internal sealed interface SnapshotQuery<out T> {
    class Answer<T>(val value: T, val partial: Boolean) : SnapshotQuery<T>
    data object Missing : SnapshotQuery<Nothing>
    data object Stale : SnapshotQuery<Nothing>
    class Failure(val cause: Exception) : SnapshotQuery<Nothing>
}

/** Freshness suppliers may call strategies and shadow lookups, both of which can cancel their caller. */
internal fun CoroutineContext.inputsCurrent(check: () -> Boolean): Boolean {
    ensureActive()
    val current = runSuspendCatching(check)
    ensureActive()
    return current.getOrThrow()
}

/** Validate on both sides of eager computation, including cancellation inside either freshness check. */
internal suspend fun <T> queryCurrentSnapshot(
    isCurrent: () -> Boolean,
    partial: Boolean = false,
    compute: suspend () -> T,
): SnapshotQuery<T> {
    val caller = currentCoroutineContext()
    if (!caller.inputsCurrent(isCurrent)) return SnapshotQuery.Stale
    val result = runSuspendCatching { compute() }
    result.onSuccess { requireEagerResult(it, caller) }
    if (!caller.inputsCurrent(isCurrent)) return SnapshotQuery.Stale
    return SnapshotQuery.Answer(result.getOrThrow(), partial)
}

private const val LAZY_QUERY_RESULT = "Consume traversal results inside the snapshot query"

/** Reject standard lazy carriers before returning them, even if the query was cancelled during computation. */
private fun requireEagerResult(result: Any?, caller: CoroutineContext) {
    if (result is BaseStream<*, *>) {
        result.use {
            caller.ensureActive()
            error(LAZY_QUERY_RESULT)
        }
    }
    caller.ensureActive()
    when (result) {
        is Flow<*>, is Sequence<*>, is Iterator<*>, is Spliterator<*> -> error(LAZY_QUERY_RESULT)
        is Iterable<*> -> check(result is Collection<*>) { LAZY_QUERY_RESULT }
    }
}
