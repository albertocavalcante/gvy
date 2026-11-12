// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.contentHash
import com.github.albertocavalcante.gvy.source.DocumentChange
import com.github.albertocavalcante.gvy.source.DocumentChangeRejectionReason
import com.github.albertocavalcante.gvy.source.DocumentChangeResult
import com.github.albertocavalcante.gvy.source.applyOrderedDocumentChanges
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Text and the client's version captured from the same buffer state. Null means unversioned input. */
data class DocumentSnapshot(val content: String, val version: Int?) {
    /** SHA-256 of [content], computed once per buffer state (readiness checks run on every navigation). */
    internal val contentHash: String by lazy { contentHash(content) }
}

/** Identity of one open/close lifecycle; accepted edits retain it. */
internal class DocumentIncarnation

/** A buffer's state and the lifecycle it belongs to, read together. */
internal class OpenBuffer(val snapshot: DocumentSnapshot, val incarnation: DocumentIncarnation)

internal sealed interface BufferChangeResult {
    data class Applied(val snapshot: DocumentSnapshot) : BufferChangeResult
    data class Invalid(val changeIndex: Int, val reason: DocumentChangeRejectionReason) : BufferChangeResult
    data object Stale : BufferChangeResult
    data object RetiredBase : BufferChangeResult
}

class DocumentProvider {
    private class OpenDocument(
        val snapshot: DocumentSnapshot,
        val incarnation: DocumentIncarnation = DocumentIncarnation(),
    )

    private val documents = ConcurrentHashMap<URI, OpenDocument>()
    private val lifecycleLock = Any()

    fun get(uri: URI): String? = documents[uri]?.snapshot?.content

    fun getSnapshot(uri: URI): DocumentSnapshot? = documents[uri]?.snapshot

    internal fun getIncarnation(uri: URI): DocumentIncarnation? = documents[uri]?.incarnation

    internal fun getBuffer(uri: URI): OpenBuffer? = documents[uri]?.let { OpenBuffer(it.snapshot, it.incarnation) }

    /** Replace an unversioned buffer, retaining the existing API for internal callers. */
    fun put(uri: URI, content: String) {
        synchronized(lifecycleLock) {
            documents[uri] = OpenDocument(DocumentSnapshot(content, null))
        }
    }

    /** Open a buffer. A new open/close lifecycle may restart the client's version sequence. */
    fun put(uri: URI, content: String, version: Int) {
        synchronized(lifecycleLock) {
            documents[uri] = OpenDocument(DocumentSnapshot(content, version))
        }
    }

    /**
     * Accept a newer full-sync state only for an existing buffer. Null [content] means no text change.
     * Returns the accepted state, or null for a closed buffer or an obsolete/duplicate version.
     */
    fun update(uri: URI, content: String?, version: Int): DocumentSnapshot? = synchronized(lifecycleLock) {
        val current = documents[uri] ?: return@synchronized null
        if (current.snapshot.version != null && version <= current.snapshot.version) {
            null
        } else {
            DocumentSnapshot(content ?: current.snapshot.content, version).also {
                documents[uri] = OpenDocument(it, current.incarnation)
            }
        }
    }

    /**
     * Prepare a batch against exactly [expectedBase], then install its final text in one transition.
     * Identity is checked before version validation and again after preparation, including invalid batches.
     * Retired bases are rejected without replaying their edits against different text. Preparation does not
     * hold the lifecycle lock. Callers must still serialize notification arrival and fence publication.
     */
    internal fun applyChanges(
        uri: URI,
        expectedBase: DocumentSnapshot,
        version: Int,
        changes: List<DocumentChange>,
    ): BufferChangeResult {
        val rejection: BufferChangeResult? = synchronized(lifecycleLock) {
            when {
                documents[uri]?.snapshot !== expectedBase -> BufferChangeResult.RetiredBase
                expectedBase.version != null && version <= expectedBase.version -> BufferChangeResult.Stale
                else -> null
            }
        }
        if (rejection != null) return rejection

        val prepared = applyOrderedDocumentChanges(expectedBase.content, changes)
        return synchronized(lifecycleLock) {
            val current = documents[uri]
            if (current == null || current.snapshot !== expectedBase) {
                BufferChangeResult.RetiredBase
            } else {
                when (prepared) {
                    is DocumentChangeResult.Rejected -> BufferChangeResult.Invalid(
                        prepared.changeIndex,
                        prepared.reason,
                    )
                    is DocumentChangeResult.Applied -> {
                        val accepted = DocumentSnapshot(prepared.text, version)
                        documents[uri] = OpenDocument(accepted, current.incarnation)
                        BufferChangeResult.Applied(accepted)
                    }
                }
            }
        }
    }

    fun remove(uri: URI) {
        synchronized(lifecycleLock) {
            documents.remove(uri)
        }
    }

    /**
     * Linearize a short, non-suspending publication against document mutations. Identity matters:
     * a reopened buffer may have exactly the same text and version as the captured state.
     * Resolve external collaborators before entering this boundary.
     */
    internal fun withCurrentSnapshot(uri: URI, captured: DocumentSnapshot, action: () -> Unit): Boolean =
        synchronized(lifecycleLock) {
            if (documents[uri]?.snapshot !== captured) {
                false
            } else {
                action()
                true
            }
        }

    /** Remove and clear only the incarnation targeted by a close, without clearing a reentrant reopen. */
    internal fun removeIfCurrent(uri: URI, captured: DocumentIncarnation, afterRemove: () -> Unit): Boolean =
        synchronized(lifecycleLock) {
            if (documents[uri]?.incarnation !== captured) {
                false
            } else {
                documents.remove(uri)
                afterRemove()
                true
            }
        }

    fun snapshot(): Map<URI, String> = documents.mapValues { it.value.snapshot.content }

    fun getAllUris(): Set<URI> = documents.keys.toSet()
}
