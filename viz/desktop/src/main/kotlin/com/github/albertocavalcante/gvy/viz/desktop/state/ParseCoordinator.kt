// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.viz.desktop.state

import com.github.albertocavalcante.gvy.concurrent.OnceFlag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All public operations and callbacks are confined to [scope]'s UI dispatcher. */
internal class ParseCoordinator(
    private val scope: CoroutineScope,
    private val background: CoroutineDispatcher,
    private val parse: suspend (ParseSnapshot, OnceFlag) -> ParseOutput,
    private val publish: (ParseOutput) -> Unit,
    private val debounceMillis: Long,
) {
    private data class Request(val generation: Long, val snapshot: ParseSnapshot, val cancelled: OnceFlag)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private var generation = 0L
    private var debounce: Job? = null
    private var token: OnceFlag? = null
    private var closed = false

    init {
        scope.launch {
            for (request in requests) process(request)
        }
    }

    // Parser backends may throw different exceptions; surface them as current-generation diagnostics.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun process(request: Request) {
        if (request.cancelled.isSet) return
        val result = try {
            withContext(background) { parse(request.snapshot, request.cancelled) }
        } catch (cancelled: CancellationException) {
            if (!scope.isActive) throw cancelled
            if (request.cancelled.isSet) return
            ParseOutput(null, listOf(CodeError("Parse cancelled: ${cancelled.message}")))
        } catch (failure: Exception) {
            ParseOutput(null, listOf(CodeError("Parse error: ${failure.message}")))
        }
        if (!closed && request.generation == generation) publish(result)
    }

    fun invalidate(): Long {
        generation++
        debounce?.cancel()
        token?.trySet() // cancels the pending request; a token is never cleared
        return generation
    }

    fun submit(snapshot: ParseSnapshot, deferred: Boolean) {
        val nextGeneration = invalidate()
        val cancelled = OnceFlag()
        token = cancelled
        val request = Request(nextGeneration, snapshot, cancelled)
        debounce = scope.launch {
            if (deferred) delay(debounceMillis)
            requests.trySend(request)
        }
    }

    fun isCurrent(value: Long): Boolean = !closed && value == generation

    fun close() {
        invalidate()
        closed = true
        requests.close()
    }
}
