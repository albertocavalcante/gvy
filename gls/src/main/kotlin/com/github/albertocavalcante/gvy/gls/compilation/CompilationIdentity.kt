// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentIncarnation
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Inputs that must agree before callers can share a pending compilation. */
internal class CompilationIdentity(
    val content: String,
    val configurationFingerprint: String,
    val document: DocumentSnapshot?,
    /** The open/close lifecycle [document] was read from; its work publishes only while that lifecycle is open. */
    val incarnation: DocumentIncarnation? = null,
) {
    fun matches(other: CompilationIdentity): Boolean = content == other.content &&
        configurationFingerprint == other.configurationFingerprint && document === other.document &&
        incarnation === other.incarnation
}

/**
 * A pending job retains its immutable inputs, including an open buffer's reference identity, and the fence
 * [ticket] it compiles under. A waiter whose job ended while that ticket is still current knows the job's
 * owner cancelled it, rather than invalidation retiring it (#1343).
 */
internal class PendingWork<I, T : Any>(
    val identity: I,
    val deferred: Deferred<T>,
    val ticket: ArtifactOwnership.Ticket,
    val delivery: PendingDelivery? = null,
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
    suspend fun awaitUnlessRetired(): T? = select<T?> {
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

internal typealias PendingCompilation = PendingWork<CompilationIdentity, CompilationResult>

/** Detach under the ownership fence, then signal and cancel outside it so inline waiters can re-enter. */
internal fun interface RetiredCompilations {
    fun retire()
}

/** A later incompatible job supersedes selection, but retirement must still wake the earlier job's waiters. */
private class ActiveJobs<I, T : Any>(val members: List<PendingWork<I, T>>, val selected: PendingWork<I, T>?) :
    RetiredCompilations {
    /** A request joining an older producer still makes that producer the latest choice for unversioned readers. */
    fun select(job: PendingWork<I, T>): ActiveJobs<I, T> = if (selected === job) this else ActiveJobs(members, job)

    fun without(job: PendingWork<I, T>): ActiveJobs<I, T>? {
        if (job !in members) return this
        val remaining = members - job
        return if (remaining.isEmpty()) null else ActiveJobs(remaining, selected.takeUnless { it === job })
    }

    override fun retire() = members.forEach { it.retire() }
}

internal class PendingRegistry<I, T : Any>(private val deliveryFor: ((I) -> PendingDelivery)? = null) {
    private val publishedJobs = SwappableConcurrentMap { ConcurrentHashMap<URI, ActiveJobs<I, T>>() }
    private val jobs: ConcurrentHashMap<URI, ActiveJobs<I, T>> get() = publishedJobs.map

    operator fun get(uri: URI): PendingWork<I, T>? = jobs[uri]?.selected

    val size: Int get() = jobs.size

    fun register(
        uri: URI,
        identity: I,
        ticket: ArtifactOwnership.Ticket,
        matches: (I) -> Boolean,
        create: () -> Deferred<T>,
    ): PendingWork<I, T> {
        val map = jobs
        val delivery = deliveryFor?.invoke(identity)
        while (true) {
            val previous = map[uri]
            // Membership is immutable. Inspect every live producer outside the map lock, newest first,
            // then confirm that the same membership still owns this URI before sharing or installing.
            val compatible = previous?.members?.firstOrNull { it.isPending && matches(it.identity) }
            var reused: PendingWork<I, T>? = null
            var created: PendingWork<I, T>? = null
            map.compute(uri) { _, current ->
                if (current !== previous) return@compute current
                if (compatible != null && compatible.isPending) {
                    compatible.delivery?.enroll()
                    reused = compatible
                    return@compute checkNotNull(current).select(compatible)
                }
                val replacement = PendingWork(identity, create(), ticket, delivery)
                delivery?.enroll()
                created = replacement
                if (replacement.isPending) {
                    ActiveJobs(listOf(replacement) + current?.members.orEmpty(), replacement)
                } else {
                    current
                }
            }
            reused?.let { return it }
            created?.let { job ->
                job.deferred.invokeOnCompletion {
                    map.computeIfPresent(uri) { _, active -> active.without(job) }
                    // A racing join either enrolled under the URI lock or selected a different producer.
                    job.delivery?.finishEnrollment()
                }
                return job
            }
        }
    }

    fun detach(uri: URI): RetiredCompilations? = jobs.remove(uri)

    fun detach(): RetiredCompilations {
        // Callers hold ArtifactOwnership's monitor; one atomic swap keeps this correct without it as well.
        val retired = publishedJobs.swapEmpty()
        return RetiredCompilations { retired.values.forEach { it.retire() } }
    }
}
