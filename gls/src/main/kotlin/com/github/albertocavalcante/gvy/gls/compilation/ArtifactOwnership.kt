package com.github.albertocavalcante.gvy.gls.compilation

import java.net.URI

/**
 * The publication boundary shared by every artifact derived from a source file: the compilation cache, the
 * symbol index and (through [acquire]) the semantic index.
 *
 * Work takes a [Ticket] before it reads its inputs: source text, configuration, classpath, worker. [retire]
 * (one file) and [retireAll] (after a configuration or worker change) make every outstanding ticket stale, so
 * work that started earlier can no longer write or remove facts, while work started afterwards publishes
 * normally. A new open/close lifecycle of a document [advance]s the file without discarding its facts.
 *
 * Retirement and its cleanup, and every [commit], run under one monitor: a check and its write cannot
 * interleave with an invalidation, and no ticket can be issued between invalidation's separate removal steps.
 * Keep the work inside short and non-suspending; build indexes before committing them.
 */
class ArtifactOwnership {
    /** Ownership of [uri]'s artifacts as of one generation. */
    class Ticket internal constructor(val uri: URI, internal val epoch: Long, internal val stamp: Long)

    private val monitor = Any()
    private var epoch = 0L
    private var lastStamp = 0L

    // Only files retired since the last global retirement are listed; the others are at stamp 0.
    private val stamps = HashMap<URI, Long>()

    internal fun begin(uri: URI): Ticket = acquire(uri) {}.first

    /** Takes a ticket and runs [alsoAcquire] (another index's own token) atomically with respect to retirement. */
    internal fun <T> acquire(uri: URI, alsoAcquire: () -> T): Pair<Ticket, T> = synchronized(monitor) {
        ticketFor(uri) to alsoAcquire()
    }

    internal fun isCurrent(ticket: Ticket): Boolean = synchronized(monitor) { isCurrentLocked(ticket) }

    /** Runs [write] only while [ticket] is current, and says whether it ran. */
    internal fun commit(ticket: Ticket, write: () -> Unit): Boolean = synchronized(monitor) {
        isCurrentLocked(ticket).also { if (it) write() }
    }

    /**
     * Makes every outstanding ticket for [uri] stale, then runs [cleanup] before another ticket can be taken.
     * Returns the first ticket of the new generation, for follow-up work owned by this retirement.
     */
    internal fun retire(uri: URI, cleanup: () -> Unit = {}): Ticket = synchronized(monitor) {
        stamps[uri] = ++lastStamp
        cleanup()
        ticketFor(uri)
    }

    /**
     * [retire]s [uri] unless [isOpen] says an editor buffer owns it again, checked under the same monitor that a
     * reopen [advance]s through. Returns null when it is open.
     */
    internal fun retireClosed(uri: URI, isOpen: (URI) -> Boolean, cleanup: () -> Unit): Ticket? =
        synchronized(monitor) { if (isOpen(uri)) null else retire(uri, cleanup) }

    /** Fences outstanding work on [uri] without discarding its facts: a document opened or closed again. */
    internal fun advance(uri: URI): Ticket = retire(uri)

    /** Makes every outstanding ticket stale, then runs [cleanup] before another ticket can be taken. */
    internal fun retireAll(cleanup: () -> Unit) {
        synchronized(monitor) {
            epoch++
            stamps.clear()
            cleanup()
        }
    }

    private fun ticketFor(uri: URI) = Ticket(uri, epoch, stamps[uri] ?: 0L)

    private fun isCurrentLocked(ticket: Ticket): Boolean =
        ticket.epoch == epoch && ticket.stamp == (stamps[ticket.uri] ?: 0L)
}
