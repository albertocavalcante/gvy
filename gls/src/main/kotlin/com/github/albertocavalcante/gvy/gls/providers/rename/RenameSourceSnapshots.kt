// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.io.IOException
import java.net.URI

internal fun interface RenameDocumentSource {
    fun read(uri: URI): DocumentSnapshot?
}

/** One rename request's captured input. Readers run outside compilation and document-store locks. */
internal class RenameSourceSnapshots(private val source: RenameDocumentSource) {
    private val captured = LinkedHashMap<URI, DocumentSnapshot>()

    fun capture(uri: URI): DocumentSnapshot = captured.getOrPut(uri) { read(uri) }

    fun validate() {
        captured.forEach { (uri, expected) ->
            if (read(uri) != expected) changed(uri)
        }
    }

    private fun read(uri: URI): DocumentSnapshot = try {
        source.read(uri) ?: changed(uri)
    } catch (_: IOException) {
        changed(uri)
    } catch (_: SecurityException) {
        changed(uri)
    }

    fun changed(uri: URI): Nothing = throw ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "Source changed or is unavailable during rename: $uri", null),
    )
}
