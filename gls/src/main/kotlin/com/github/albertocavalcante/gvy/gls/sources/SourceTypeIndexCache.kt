// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded detached syntax indexes; producers belong to the server and never capture a borrowing request. */
internal class SourceTypeIndexCache(
    serverScope: CoroutineScope,
    private val build: suspend (language: String, content: String, checkCancelled: () -> Unit) -> SourceTypeIndex,
) : AutoCloseable {
    private val completed = linkedMapOf<Key, SourceTypeIndex>()
    private var closed = false
    private val producers = SourceRequestCoalescer<Key, SourceTypeIndex>(
        parentJob = serverScope.coroutineContext[Job],
        parallelism = 1,
    )

    suspend fun index(language: String, contentDigest: String, content: String): SourceTypeIndex {
        currentCoroutineContext().ensureActive()
        val key = Key(language, contentDigest)
        cached(key)?.let { return it }
        return producers.acquire(key) {
            cached(key)?.let { return@acquire it }
            val producer = currentCoroutineContext()
            val value = withContext(Dispatchers.Default) { build(language, content, producer::ensureActive) }
            producer.ensureActive()
            synchronized(completed) {
                if (closed) throw CancellationException("Source type indexes are closed")
                completed[key] = value
                while (completed.size > MAX_INDEXES) completed.remove(completed.keys.first())
            }
            value
        }
    }

    private fun cached(key: Key): SourceTypeIndex? = synchronized(completed) {
        if (closed) throw CancellationException("Source type indexes are closed")
        completed.remove(key)?.also { completed[key] = it }
    }

    override fun close() {
        producers.close()
        synchronized(completed) {
            closed = true
            completed.clear()
        }
    }

    private data class Key(val language: String, val content: String)

    private companion object {
        const val MAX_INDEXES = 16
    }
}
