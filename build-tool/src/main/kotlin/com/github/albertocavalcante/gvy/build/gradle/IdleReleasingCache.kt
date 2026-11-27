// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** What the idle decision needs to know about one cached entry. */
internal data class EntryActivity(val lastUsedNanos: Long, val leases: Int)

/**
 * Pure idle decision: the keys whose entries hold no lease and were last used at least [idleTimeout] before
 * [nowNanos]. Deterministic for a given input; no clock, no IO.
 */
internal fun <K> selectIdleKeys(activity: Map<K, EntryActivity>, nowNanos: Long, idleTimeout: Duration): List<K> =
    activity.filterValues { entry ->
        entry.leases == 0 && (nowNanos - entry.lastUsedNanos).nanoseconds >= idleTimeout
    }.keys.toList()

/**
 * A thread-safe cache whose entries are closed after sitting idle. Entries in use (leased) are never released.
 *
 * Time comes from [clockNanos] and sweeping is driven by calling [releaseIdle], so the policy is testable without
 * real time or threads; the owner decides how to schedule the sweep.
 *
 * [close] is final. It sets the closed flag under the lock that every insertion holds while it checks the flag, so
 * nothing is inserted once the flag is set, and it then closes every entry inserted before. Nothing stays cached
 * after close, however the callers' threads are scheduled.
 */
internal class IdleReleasingCache<K : Any, V : Any>(
    private val create: (K) -> V,
    private val closeValue: (K, V) -> Unit,
    private val clockNanos: () -> Long,
    private val idleTimeout: () -> Duration,
) : AutoCloseable {
    class Entry<V>(val value: V, var lastUsedNanos: Long, var leases: Int = 0)

    private val lock = Any()
    private val entries = HashMap<K, Entry<V>>()

    /** Set once, by [close]. Guarded by [lock], like [entries]. */
    private var closed = false

    val size: Int get() = synchronized(lock) { entries.size }

    val keys: List<K> get() = synchronized(lock) { entries.keys.toList() }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * Returns the cached value, creating it if absent, and marks it used now. Returns null once the cache is closed,
     * without creating anything.
     */
    fun get(key: K): V? = synchronized(lock) { if (closed) null else touch(key).value }

    /**
     * A borrowed value; closing it ends the borrow and restarts the idle clock. A lease without an [entry] was taken
     * after [IdleReleasingCache.close]: its value was never cached, so closing the lease closes the value.
     */
    inner class Lease internal constructor(private val key: K, val value: V, private val entry: Entry<V>?) :
        AutoCloseable {
        private var open = true

        override fun close() {
            synchronized(lock) {
                if (!open) return
                open = false
                entry?.let {
                    it.leases--
                    it.lastUsedNanos = clockNanos()
                }
            }
            if (entry == null) closeQuietly(key, value)
        }
    }

    /**
     * Borrows the value for [key], protected from idle release until the lease is closed. Once the cache is closed
     * nothing is cached: the lease holds a value of its own, which is closed with the lease.
     */
    fun acquire(key: K): Lease = synchronized(lock) {
        if (closed) {
            Lease(key, create(key), entry = null)
        } else {
            val entry = touch(key).also { it.leases++ }
            Lease(key, entry.value, entry)
        }
    }

    /** Runs [block] with the value for [key] protected from idle release until [block] returns. */
    fun <T> lease(key: K, block: (V) -> T): T = acquire(key).use { block(it.value) }

    /** Closes and removes every idle, unleased entry. Returns how many were released. */
    fun releaseIdle(): Int {
        val released = synchronized(lock) {
            val activity = entries.mapValues { (_, entry) -> EntryActivity(entry.lastUsedNanos, entry.leases) }
            selectIdleKeys(activity, clockNanos(), idleTimeout()).mapNotNull { key ->
                entries.remove(key)?.let { key to it.value }
            }
        }
        // Closing may block on in-flight operations, so it runs outside the lock.
        released.forEach { (key, value) -> closeQuietly(key, value) }
        return released.size
    }

    /** Closes and removes the entries for the keys matching [predicate], leased or not. */
    fun remove(predicate: (K) -> Boolean) {
        val removed = synchronized(lock) {
            entries.keys.filter(predicate).mapNotNull { key -> entries.remove(key)?.let { key to it.value } }
        }
        removed.forEach { (key, value) -> closeQuietly(key, value) }
    }

    /** One failing close must not leave the remaining entries open, so failures are contained per entry. */
    private fun closeQuietly(key: K, value: V) {
        runCatching { closeValue(key, value) }.onFailure { if (it is Error) throw it }
    }

    /**
     * Closes every entry, leased or not, and caches nothing afterwards; see [get] and [acquire]. Calling it again
     * does nothing.
     */
    override fun close() {
        // Once the flag is set nothing more is cached, so the entries remove takes are the last there will be.
        synchronized(lock) { closed = true }
        remove { true }
    }

    private fun touch(key: K): Entry<V> {
        val now = clockNanos()
        val entry = entries.getOrPut(key) { Entry(create(key), now) }
        entry.lastUsedNanos = now
        return entry
    }
}
