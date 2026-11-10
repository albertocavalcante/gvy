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
 * compile and workspace query, which must not (#1316). So each open buffer's listed spelling is computed once per
 * model, at [opened] on the notification thread, and [shadowed] reads the cache: only a buffer whose entry
 * predates the current model is translated again, once.
 */
internal class ListedShadows(
    private val documentProvider: DocumentProvider?,
    private val workspaceManager: WorkspaceManager,
) {
    /** One open buffer's listed spelling, when it is another URI, under the model it was translated against. */
    private class Entry(val workspace: WorkspaceModel, val listed: URI?)

    private val entries = ConcurrentHashMap<URI, Entry>()

    /** A buffer for [uri] opened: translated now, off the request path. */
    fun opened(uri: URI) {
        listedFor(uri, workspaceManager.current())
    }

    /** A buffer for [uri] closed: its listed spelling when it is another URI, and the entry is dropped. */
    fun closed(uri: URI): URI? = (entries.remove(uri) ?: Entry(workspaceManager.current(), translate(uri))).listed

    /** Each open buffer's listed spelling that is another URI, unless a buffer is open under that one too. */
    fun shadowed(): Set<URI> {
        val open = documentProvider?.getAllUris().orEmpty()
        if (open.isEmpty()) return emptySet()
        entries.keys.retainAll(open)
        val workspace = workspaceManager.current()
        return open.mapNotNullTo(HashSet()) { uri -> listedFor(uri, workspace)?.takeIf { it !in open } }
    }

    private fun listedFor(uri: URI, workspace: WorkspaceModel): URI? =
        entries[uri]?.takeIf { it.workspace === workspace }?.listed
            ?: translate(uri).also { entries[uri] = Entry(workspace, it) }

    private fun translate(uri: URI): URI? {
        val path = UriPathConverter.toPath(uri) ?: return null
        return workspaceManager.listedSpellings(listOf(path))[path]?.toUri()?.takeIf { it != uri }
    }
}
