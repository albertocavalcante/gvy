// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Listed sources shadowed by a buffer the client opened under another spelling (#1446): a symlinked root's real
 * path, or another case or normalisation on a case-insensitive file system. While that buffer is open it stands
 * for the file, so its listed copy must not compile beside it, nor answer workspace-wide queries a second time.
 *
 * Translating a spelling can read the disk ([WorkspaceManager.listedSpellings]), and [shadowed] runs on every
 * compile and workspace query, which must not (#1316). So each open buffer's listed spelling is computed at
 * [opened], on the notification thread, and [shadowed] reads the cache. An entry stays valid for one
 * [WorkspaceManager.spellingGeneration]: a dependency or target re-stamp keeps it; a root or listing change makes
 * the next query translate that buffer again, once.
 */
internal class ListedShadows(
    private val documentProvider: DocumentProvider?,
    private val workspaceManager: WorkspaceManager,
) {
    /** One open buffer's listed spelling, when it is another URI, and the spelling generation it was found in. */
    private class Entry(val generation: Any, val listed: URI?)

    private val entries = ConcurrentHashMap<URI, Entry>()

    /** A buffer for [uri] opened: translated now, off the request path. */
    fun opened(uri: URI) {
        listedFor(uri, workspaceManager.spellingGeneration())
    }

    /** A buffer for [uri] closed: its listed spelling when it is another URI, and the entry is dropped. */
    fun closed(uri: URI): URI? {
        val entry = entries.remove(uri)
        // A buffer opened as listed has an entry holding null: only a buffer with no entry is translated now.
        return if (entry != null) entry.listed else translate(uri)
    }

    /**
     * The listing or roots changed (#1446): translate every open buffer now, off the request path, so the next
     * compile or workspace query finds its entries current. Call it where the model is published.
     */
    fun refresh() {
        val open = documentProvider?.getAllUris().orEmpty()
        entries.keys.retainAll(open)
        if (open.isEmpty()) return
        val generation = workspaceManager.spellingGeneration()
        open.forEach { uri -> listedFor(uri, generation) }
    }

    /** Each open buffer's listed spelling that is another URI, unless a buffer is open under that one too. */
    fun shadowed(): Set<URI> {
        val open = documentProvider?.getAllUris().orEmpty()
        if (open.isEmpty()) return emptySet()
        entries.keys.retainAll(open)
        val generation = workspaceManager.spellingGeneration()
        return open.mapNotNullTo(HashSet()) { uri -> listedFor(uri, generation)?.takeIf { it !in open } }
    }

    private fun listedFor(uri: URI, generation: Any): URI? {
        val entry = entries[uri]?.takeIf { it.generation === generation }
        return if (entry != null) entry.listed else translate(uri).also { entries[uri] = Entry(generation, it) }
    }

    private fun translate(uri: URI): URI? {
        val path = UriPathConverter.toPath(uri) ?: return null
        return workspaceManager.listedSpellings(listOf(path))[path]?.toUri()?.takeIf { it != uri }
    }
}
