// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.locks.ReentrantLock

/**
 * Coordinates workspace snapshots with the service updates that publish them and retire dependent caches.
 * Compilations hold a value snapshot, never this gate, while parsing.
 */
internal class WorkspaceModelTransitionGate {
    internal data class Snapshot(val model: WorkspaceModel, val generation: Long)

    private sealed interface ReadState {
        data class Ready(val snapshot: Snapshot) : ReadState
        data class Waiting(val completion: Deferred<Unit>) : ReadState
        data class Failed(val cause: Throwable) : ReadState
    }

    private val writerLock = ReentrantLock()
    private val monitor = Any()
    private var generation = 0L
    private var readEpoch = 0L
    private var transition: CompletableDeferred<Unit>? = null
    private var failure: Throwable? = null

    /** Runs one synchronous model/cache transaction. Overlapping writers are serialized. */
    fun <T> transition(action: () -> T, shouldAdvanceGeneration: (T) -> Boolean = { true }): T {
        writerLock.lock()
        try {
            val completion = synchronized(monitor) {
                check(transition == null) { "Workspace model transitions must not be nested" }
                readEpoch++
                CompletableDeferred<Unit>().also { transition = it }
            }
            var failed: Throwable? = null
            var advancesGeneration = true
            try {
                return action().also { result -> advancesGeneration = shouldAdvanceGeneration(result) }
            } catch (cause: Throwable) {
                failed = cause
                throw cause
            } finally {
                synchronized(monitor) {
                    if (failed != null || advancesGeneration) generation++
                    // A successful action is not proof that it repaired a previous partial update. Keep the
                    // compiler fail-closed until this service is rebuilt with a coherent model and cache state.
                    if (failed != null) failure = failed
                    transition = null
                }
                val cause = failed
                if (cause == null) completion.complete(Unit) else completion.completeExceptionally(cause)
            }
        } finally {
            writerLock.unlock()
        }
    }

    /** Waits cancellably until the model and its dependent service state are ready together. */
    suspend fun awaitSnapshot(currentModel: () -> WorkspaceModel): Snapshot {
        while (true) {
            val state = synchronized(monitor) {
                when {
                    transition != null -> ReadState.Waiting(checkNotNull(transition))
                    failure != null -> ReadState.Failed(checkNotNull(failure))
                    else -> ReadState.Ready(Snapshot(currentModel(), generation))
                }
            }
            when (state) {
                is ReadState.Ready -> return state.snapshot
                is ReadState.Failed -> throw IllegalStateException("Workspace model transition failed", state.cause)
                is ReadState.Waiting -> state.completion.await()
            }
        }
    }

    /** Checks the snapshot's model and that no cache/configuration transition crossed its lifetime. */
    fun isCurrent(
        snapshot: Snapshot,
        currentModel: () -> WorkspaceModel,
        hasSameInputs: (WorkspaceModel, WorkspaceModel) -> Boolean = { expected, current -> expected === current },
    ): Boolean = synchronized(monitor) {
        isCurrentUnderLock(snapshot, currentModel, hasSameInputs)
    }

    private fun isCurrentUnderLock(
        snapshot: Snapshot,
        currentModel: () -> WorkspaceModel,
        hasSameInputs: (WorkspaceModel, WorkspaceModel) -> Boolean,
    ): Boolean =
        transition == null && failure == null && snapshot.generation == generation &&
            currentModel().let { current -> current === snapshot.model || hasSameInputs(snapshot.model, current) }

    fun isReady(): Boolean = synchronized(monitor) { transition == null && failure == null }

    /** A permit is invalidated at the start of every transition, including no-op transitions. */
    fun readPermit(): (() -> Boolean)? = synchronized(monitor) {
        if (transition != null || failure != null) return@synchronized null
        val captured = readEpoch
        val current: () -> Boolean = { synchronized(monitor) { transition == null && failure == null && readEpoch == captured } }
        current
    }

    /** Runs a short publication while a transition cannot begin; [hasSameInputs] must be pure. */
    fun <T> publishIfCurrent(
        snapshot: Snapshot,
        currentModel: () -> WorkspaceModel,
        hasSameInputs: (WorkspaceModel, WorkspaceModel) -> Boolean = { expected, current -> expected === current },
        action: () -> T,
    ): T? =
        synchronized(monitor) {
            if (isCurrentUnderLock(snapshot, currentModel, hasSameInputs)) {
                action()
            } else {
                null
            }
        }

}

/** Run a query without holding the gate and discard results if a transition crossed it. */
internal inline fun <T> guardedWorkspaceRead(
    isReady: () -> Boolean,
    permit: () -> (() -> Boolean)?,
    fallback: T,
    read: () -> T,
): T {
    if (!isReady()) return fallback
    val current = permit() ?: return fallback
    val result = read()
    return if (isReady() && current()) result else fallback
}
