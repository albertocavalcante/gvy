package com.github.albertocavalcante.gvy.gls.services

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Text and the client's version captured from the same buffer state. Null means unversioned input. */
data class DocumentSnapshot(val content: String, val version: Int?)

class DocumentProvider {
    private val documents = ConcurrentHashMap<URI, DocumentSnapshot>()

    fun get(uri: URI): String? = documents[uri]?.content

    fun getSnapshot(uri: URI): DocumentSnapshot? = documents[uri]

    /** Replace an unversioned buffer, retaining the existing API for internal callers. */
    fun put(uri: URI, content: String) {
        documents[uri] = DocumentSnapshot(content, null)
    }

    /** Open a buffer. A new open/close lifecycle may restart the client's version sequence. */
    fun put(uri: URI, content: String, version: Int) {
        documents[uri] = DocumentSnapshot(content, version)
    }

    /**
     * Accept a newer full-sync state only for an existing buffer. Null [content] means no text change.
     * Returns the accepted state, or null for a closed buffer or an obsolete/duplicate version.
     */
    fun update(uri: URI, content: String?, version: Int): DocumentSnapshot? {
        var accepted: DocumentSnapshot? = null
        documents.computeIfPresent(uri) { _, current ->
            if (current.version != null && version <= current.version) {
                current
            } else {
                DocumentSnapshot(content ?: current.content, version).also { accepted = it }
            }
        }
        return accepted
    }

    fun remove(uri: URI) {
        documents.remove(uri)
    }

    fun snapshot(): Map<URI, String> = documents.mapValues { it.value.content }

    fun getAllUris(): Set<URI> = documents.keys.toSet()
}
