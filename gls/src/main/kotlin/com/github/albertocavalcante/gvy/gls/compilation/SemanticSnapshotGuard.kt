package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.semantics.db.ImplementationDocument
import java.net.URI

/** Serializes invalidation with snapshot capture; no lock is held while compiling or querying. */
internal class SemanticSnapshotGuard(private val readSnapshot: () -> Map<URI, ImplementationDocument>) {
    class Generation

    private val monitor = Any()
    private var generation = Generation()

    fun begin(): Generation = synchronized(monitor) { generation }

    fun capture(expected: Generation): Map<URI, ImplementationDocument>? = synchronized(monitor) {
        if (generation === expected) readSnapshot() else null
    }

    fun invalidate(action: () -> Unit) = synchronized(monitor) {
        // Change even if invalidation fails: an acquisition must not accept partially invalidated state.
        generation = Generation()
        action()
    }
}
