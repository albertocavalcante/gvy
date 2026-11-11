package com.github.albertocavalcante.gvy.gls.e2e

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Cancelling the LSP4J future also sends the protocol cancellation notification for an unfinished request. */
internal fun <T> CompletableFuture<T>.awaitResponse(timeoutMs: Long, checkActive: () -> Unit = {}): T = try {
    checkActive()
    get(timeoutMs, TimeUnit.MILLISECONDS)
} finally {
    if (!isDone) cancel(true)
}
