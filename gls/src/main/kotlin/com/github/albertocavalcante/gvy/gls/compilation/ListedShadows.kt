package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.UriPathConverter
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import java.net.URI

/**
 * Listed sources shadowed by a buffer the client opened under another spelling (#1446): a symlinked root's real
 * path, or another case or normalisation on a case-insensitive file system. While that buffer is open it stands
 * for the file, so its listed copy must not compile beside it, nor answer workspace-wide queries a second time.
 * Computed from the open buffers on every call, so closing the buffer ends the shadow with nothing to restore.
 */
internal class ListedShadows(
    private val documentProvider: DocumentProvider?,
    private val workspaceManager: WorkspaceManager,
) {
    /** The listed spelling of [uri] when it is another URI; null when [uri] is listed as it is, or is no file. */
    fun listedUri(uri: URI): URI? {
        val path = UriPathConverter.toPath(uri) ?: return null
        return workspaceManager.listedSpellings(listOf(path))[path]?.toUri()?.takeIf { it != uri }
    }

    /** Each open buffer's listed spelling that is another URI, unless a buffer is open under that one too. */
    fun shadowed(): Set<URI> {
        val open = documentProvider?.getAllUris().orEmpty()
        if (open.isEmpty()) return emptySet()
        val paths = open.mapNotNull { uri -> UriPathConverter.toPath(uri)?.let { uri to it } }
        val spellings = workspaceManager.listedSpellings(paths.map { it.second })
        return paths.mapNotNullTo(HashSet()) { (uri, path) ->
            spellings[path]?.toUri()?.takeIf { it != uri && it !in open }
        }
    }
}
