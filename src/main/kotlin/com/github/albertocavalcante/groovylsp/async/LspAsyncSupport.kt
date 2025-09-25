package com.github.albertocavalcante.groovylsp.async

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

/**
 * Async support utilities for LSP operations.
 * Provides bridge functions between Kotlin coroutines and Java CompletableFuture.
 */

/**
 * Extension function to convert coroutine blocks to CompletableFuture for LSP4J compatibility.
 *
 * This function must catch all exceptions (Throwable) to properly bridge between
 * coroutine and CompletableFuture error handling models.
 */
@Suppress("TooGenericExceptionCaught")
fun <T> CoroutineScope.future(block: suspend () -> T): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    launch {
        try {
            future.complete(block())
        } catch (e: Throwable) {
            future.completeExceptionally(e)
        }
    }
    return future
}
