// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.WillSaveTextDocumentParams
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal data class BufferedDocumentChange(val version: Int, val text: String?)

internal data class ManagedDocumentAdmission(
    val ticket: Any,
    val openingVersion: Int,
    val guarded: Boolean = true,
    val resolved: Boolean = false,
    val delegated: Boolean = false,
    val change: BufferedDocumentChange? = null,
    val rejectedChange: DidChangeTextDocumentParams? = null,
    val save: DidSaveTextDocumentParams? = null,
    val willSave: WillSaveTextDocumentParams? = null,
)

internal data class DeferredManagedDocumentOpen(val ticket: Any, val params: DidOpenTextDocumentParams)

internal class ManagedDocumentLifecycle {
    val monitor = Any()
    val admission = AtomicReference<ManagedDocumentAdmission?>(null)

    /** Accessed under [monitor]; at most the latest reentrant open is retained. */
    var deferredOpen: DeferredManagedDocumentOpen? = null

    /** A single borrower drains pending notifications while filesystem admission runs outside [monitor]. */
    var draining: Boolean = false

    /** Updated only within the owning map's per-key compute operation. */
    var users: Int = 0
}

/** Keeps a URI's reentrant monitor stable while admission IO or notifications still use its lifecycle. */
internal class ManagedDocumentLifecycles {
    private val entries = ConcurrentHashMap<URI, ManagedDocumentLifecycle>()

    fun guarded(uri: URI): Boolean = entries[uri]?.admission?.get()?.guarded == true

    fun <T> withEntry(uri: URI, action: (ManagedDocumentLifecycle) -> T): T {
        val entry = requireNotNull(
            entries.compute(uri) { _, current ->
                val lifecycle = current ?: ManagedDocumentLifecycle()
                lifecycle.users++
                lifecycle
            },
        )
        try {
            return action(entry)
        } finally {
            // Never acquire the lifecycle monitor from map bookkeeping: delegate callbacks can reenter this map.
            entries.computeIfPresent(uri) { _, current ->
                check(current === entry)
                current.users--
                if (current.users == 0 && current.admission.get() == null) null else current
            }
        }
    }
}
