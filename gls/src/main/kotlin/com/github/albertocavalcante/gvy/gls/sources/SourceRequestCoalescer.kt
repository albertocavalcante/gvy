// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val MAX_SOURCE_ACQUISITIONS = 32
private const val SOURCE_ACQUISITION_PARALLELISM = 2

/** A request borrows work from the service lifecycle. Cancelling a borrower never cancels another borrower. */
internal class SourceRequestCoalescer<K, T>(
    parentJob: Job? = null,
    parallelism: Int = SOURCE_ACQUISITION_PARALLELISM,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)
    private val permits = Semaphore(parallelism)
    private val monitor = Any()
    private val pending = mutableMapOf<K, Pending<T>>()
    private var closed = false

    suspend fun acquire(key: K, operation: suspend () -> T): T {
        val shared = synchronized(monitor) {
            if (closed) throw CancellationException("Source acquisition is closed")
            pending[key]?.takeUnless { acquisition -> acquisition.result.isCancelled }
                ?.also { acquisition -> acquisition.readers++ }
                ?: run {
                    if (pending.size >= MAX_SOURCE_ACQUISITIONS) {
                        throw SourceStoreException(SourceFailure.LIMIT_EXCEEDED)
                    }
                    val result = scope.async(start = CoroutineStart.LAZY) { permits.withPermit { operation() } }
                    Pending(result, 1).also { acquisition -> pending[key] = acquisition }
                }
        }

        shared.result.start()
        return try {
            shared.result.await()
        } finally {
            synchronized(monitor) {
                shared.readers--
                if (shared.readers == 0) {
                    if (pending[key] === shared) pending.remove(key)
                    if (!shared.result.isCompleted) shared.result.cancel()
                }
            }
        }
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            pending.clear()
        }
        scope.cancel()
    }

    private class Pending<T>(val result: Deferred<T>, var readers: Int)
}
