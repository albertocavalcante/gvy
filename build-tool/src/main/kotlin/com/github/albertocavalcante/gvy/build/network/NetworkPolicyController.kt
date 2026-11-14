// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.network

import kotlinx.serialization.json.JsonElement

/** Serializes policy changes, admissions, and publication against one revision. */
class NetworkPolicyController(initial: JsonElement? = null) {
    private val lock = Any()
    private var current = NetworkPolicy.parse(initial, 0)
    private val operations = mutableSetOf<Admission>()

    fun snapshot(): NetworkPolicy = synchronized(lock) { current }

    /** Returns every cancellation failure after attempting all registered callbacks. */
    fun replace(input: JsonElement?): List<Throwable> {
        val callbacks: List<() -> Unit>
        val next: NetworkPolicy
        synchronized(lock) {
            next = NetworkPolicy.parse(input, Math.addExact(current.revision, 1))
            current = next
            callbacks = operations.map { it.cancel }
            operations.clear()
        }
        // Attempt every signal, including Error failures, and retain failures for caller diagnostics.
        return callbacks.mapNotNull { callback -> runCatching(callback).exceptionOrNull() }
    }

    fun admit(url: String, cancel: () -> Unit): AdmissionResult = synchronized(lock) {
        val failure = current.authorize(url)
        if (failure != null) return@synchronized AdmissionResult(null, failure)
        val admission = Admission(current.revision, cancel)
        operations.add(admission)
        AdmissionResult(admission, null)
    }

    inner class Admission internal constructor(val revision: Long, internal val cancel: () -> Unit) : AutoCloseable {
        fun check(): PolicyFailure? = synchronized(lock) {
            if (revision == current.revision && this in operations) null else PolicyFailure.STALE_POLICY
        }

        /** The action executes while replacement is fenced; keep it short and nonblocking. */
        fun publish(action: () -> Unit): Boolean = synchronized(lock) {
            if (revision != current.revision || this !in operations) return@synchronized false
            action()
            true
        }

        override fun close() {
            synchronized(lock) { operations.remove(this) }
        }
    }
}

data class AdmissionResult(val lease: NetworkPolicyController.Admission?, val failure: PolicyFailure?)
