package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentIncarnation
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select

/** Inputs that must agree before callers can share a pending compilation. */
internal class CompilationIdentity(
    val content: String,
    val configurationFingerprint: String,
    val document: DocumentSnapshot?,
    /** The open/close lifecycle [document] was read from; its work publishes only while that lifecycle is open. */
    val incarnation: DocumentIncarnation? = null,
) {
    fun matches(other: CompilationIdentity): Boolean = content == other.content &&
        configurationFingerprint == other.configurationFingerprint && document === other.document
}

/**
 * A pending job retains its immutable inputs, including an open buffer's reference identity, and the fence
 * [ticket] it compiles under. A waiter whose job ended while that ticket is still current knows the job's
 * owner cancelled it, rather than invalidation retiring it (#1343).
 */
internal class PendingCompilation(
    val identity: CompilationIdentity,
    val deferred: Deferred<CompilationResult>,
    val ticket: ArtifactOwnership.Ticket,
) {
    /**
     * Completed by the service when it retires this job, after the job can no longer be selected (#1429). A
     * cancelled job completes only when its blocking compile returns, so waiters wake on this signal instead.
     * A job that completes normally leaves it uncompleted; it is collected with the job.
     */
    val retired = CompletableDeferred<Unit>()

    /**
     * The job's result, or null once the service retires it. A job that ends by cancellation throws, as
     * [Deferred.await] does. The clause that loses the [select] is disposed, so no waiter stays registered.
     */
    suspend fun awaitUnlessRetired(): CompilationResult? = select<CompilationResult?> {
        deferred.onAwait { it }
        retired.onAwait { null }
    }

    /** Wakes this job's waiters, then cancels it. Call only once the job is unreachable for new waiters. */
    fun retire() {
        retired.complete(Unit)
        deferred.cancel()
    }

    // A registered LAZY coroutine is shareable before it becomes active.
    val isPending: Boolean get() = !deferred.isCompleted && !deferred.isCancelled
}
