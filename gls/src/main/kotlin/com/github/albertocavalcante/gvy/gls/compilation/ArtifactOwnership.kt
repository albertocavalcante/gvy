package com.github.albertocavalcante.gvy.gls.compilation

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * The open-buffer version a compile read, for ordering its writes against other compiles of the same buffer
 * (#1383). [version] is the client's LSP version, monotonic within one [incarnation] (an open/close
 * lifecycle). [isCurrent] says whether that text is still the buffer's current state.
 */
internal class BufferOrder(val incarnation: Any, val version: Int, val isCurrent: () -> Boolean)

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
    class Ticket internal constructor(
        val uri: URI,
        internal val epoch: Long,
        internal val stamp: Long,
        internal val order: BufferOrder? = null,
    )

    private val monitor = Any()
    private var epoch = 0L
    private var lastStamp = 0L

    // Only files retired since the last global retirement are listed; the others are at stamp 0.
    private val stamps = HashMap<URI, Long>()

    // The buffer order of each file's last accepted write; dropped with the file's stamp, so a reopen restarts it.
    private val committedOrders = HashMap<URI, BufferOrder>()
    private val logger = KotlinLogging.logger {}

    internal fun begin(uri: URI): Ticket = acquire(uri) {}.first

    /**
     * Takes a ticket and runs [alsoAcquire] (another index's own token) atomically with respect to retirement.
     * An [order] makes the ticket's writes respect buffer-version order: see [commit].
     */
    internal fun <T> acquire(uri: URI, order: BufferOrder? = null, alsoAcquire: () -> T): Pair<Ticket, T> =
        synchronized(monitor) { ticketFor(uri, order) to alsoAcquire() }

    /** As [acquire] without an order. */
    internal fun <T> acquire(uri: URI, alsoAcquire: () -> T): Pair<Ticket, T> = acquire(uri, null, alsoAcquire)

    internal fun isCurrent(ticket: Ticket): Boolean = synchronized(monitor) { isCurrentLocked(ticket) }

    /**
     * Runs [write] only while [ticket] is current and, for buffer work, while no newer version of the same
     * buffer lifecycle has already been written: the newest version wins whatever the completion order
     * (#1383). The buffer's current text always commits, so a client whose versions go backwards falls back
     * to commit order instead of stalling. Says whether [write] ran.
     */
    internal fun commit(ticket: Ticket, write: () -> Unit): Boolean = synchronized(monitor) {
        val accepted = isCurrentLocked(ticket) && inOrderLocked(ticket)
        if (accepted) {
            write()
            ticket.order?.let { committedOrders[ticket.uri] = it }
        }
        accepted
    }

    private fun inOrderLocked(ticket: Ticket): Boolean {
        val order = ticket.order ?: return true
        val last = committedOrders[ticket.uri]
        if (last == null || last.incarnation !== order.incarnation || last.version <= order.version) return true
        if (!order.isCurrent()) return false
        logger.debug { "${ticket.uri}: version ${order.version} after ${last.version}; accepting the current text" }
        return true
    }

    /**
     * Makes every outstanding ticket for [uri] stale, then runs [cleanup] before another ticket can be taken.
     * Returns the first ticket of the new generation, for follow-up work owned by this retirement.
     */
    internal fun retire(uri: URI, cleanup: () -> Unit = {}): Ticket = synchronized(monitor) {
        stamps[uri] = ++lastStamp
        committedOrders.remove(uri)
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
            committedOrders.clear()
            cleanup()
        }
    }

    /** Files the fence currently holds generation state for (#1341). */
    internal val trackedFileCount: Int get() = synchronized(monitor) { stamps.size }

    /** Test hook: what the garbage collector does once nothing references [uri]'s state (#1341). */
    internal fun clearUnreferencedStateForTesting(uri: URI) = Unit

    private fun ticketFor(uri: URI, order: BufferOrder? = null) = Ticket(uri, epoch, stamps[uri] ?: 0L, order)

    private fun isCurrentLocked(ticket: Ticket): Boolean =
        ticket.epoch == epoch && ticket.stamp == (stamps[ticket.uri] ?: 0L)
}
