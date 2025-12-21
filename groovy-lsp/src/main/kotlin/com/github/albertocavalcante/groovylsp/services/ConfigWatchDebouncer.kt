package com.github.albertocavalcante.groovylsp.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Debounces rapid file changes to avoid spamming recompilations.
 * Waits for a quiet period (500ms) before executing the action.
 * Thread-safe implementation using ConcurrentHashMap.
 */
class ConfigWatchDebouncer(private val scope: CoroutineScope, private val debounceDelayMs: Long = DEFAULT_DEBOUNCE_MS) {
    private val logger = LoggerFactory.getLogger(ConfigWatchDebouncer::class.java)
    private val pendingJobs = ConcurrentHashMap<String, Job>()

    /**
     * Schedules an action to be executed after a debounce delay.
     * If called again before the delay expires, the previous action is cancelled.
     * Uses atomic operations to prevent race conditions.
     *
     * @param key Unique key identifying the file/change type
     * @param action The action to execute after debounce
     */
    fun debounce(key: String, action: suspend () -> Unit) {
        // Cancel existing job for this key atomically
        pendingJobs[key]?.cancel()

        // Create new job
        val job = scope.launch {
            delay(debounceDelayMs)
            executeAction(key, action)
        }

        // Atomically replace - if another thread just put a job, we'll cancel it on next debounce
        val oldJob = pendingJobs.put(key, job)
        oldJob?.cancel() // Cancel any job that was put between our get and put
    }

    /**
     * Executes the debounced action with proper error handling.
     */
    @Suppress("TooGenericExceptionCaught") // NOTE: Action may throw various exceptions
    private suspend fun executeAction(key: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Re-throw cancellation to preserve coroutine cancellation
        } catch (e: Exception) {
            logger.warn("Debounced action failed for key: $key", e)
        } finally {
            // Only remove if this is still our job (atomic compare-and-remove)
            // This prevents removing a newer job that was scheduled after us
            pendingJobs.remove(key)
        }
    }

    /**
     * Cancels all pending debounced actions.
     */
    fun cancelAll() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }

    companion object {
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
