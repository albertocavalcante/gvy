package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Open-buffer text whose compile yielded no AST, so it can never be indexed (#1344). Readiness checks skip that
 * exact text until it changes or its fence ticket is retired: a file invalidation, a close or reopen, or a
 * configuration or worker change. At most one entry per URI; the owning index removes it with the file's facts.
 */
internal class UnparseableTexts(private val ownership: ArtifactOwnership) {
    private class Entry(val contentHash: String, val ticket: ArtifactOwnership.Ticket)

    // Swapped by [clear] (#1342). A [mark] racing it may land in either map; an entry carries its ticket, which a
    // global clear retires, so [isKnown] ignores it whichever map it is in.
    @Volatile private var entries = ConcurrentHashMap<URI, Entry>()

    /** A ticket to take before compiling [uri], so a retirement during that compile voids [mark]. */
    fun begin(uri: URI): ArtifactOwnership.Ticket = ownership.begin(uri)

    /** Records that compiling [snapshot] under [ticket] yielded no AST; ignored once [ticket] is stale. */
    fun mark(uri: URI, snapshot: DocumentSnapshot, ticket: ArtifactOwnership.Ticket) {
        if (ownership.isCurrent(ticket)) entries[uri] = Entry(snapshot.contentHash, ticket)
    }

    /** Whether [snapshot]'s text is known not to parse, under facts that are still current. */
    fun isKnown(uri: URI, snapshot: DocumentSnapshot): Boolean =
        entries[uri]?.let { it.contentHash == snapshot.contentHash && ownership.isCurrent(it.ticket) } == true

    fun remove(uri: URI) {
        entries.remove(uri)
    }

    fun clear() {
        entries = ConcurrentHashMap()
    }

    /** Entries held, for tests that prove removals leave nothing behind. */
    val size: Int get() = entries.size
}
