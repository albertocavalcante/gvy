// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.indexing

import com.github.albertocavalcante.gvy.semantics.db.ReferenceIndexSnapshot
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.net.URI

/** A request-scoped reference lookup against one database graph and one set of hidden listed spellings. */
internal class CapturedWorkspaceReferences(
    private val snapshot: ReferenceIndexSnapshot,
    private val hidden: Set<URI>,
    val isDatabaseCurrent: () -> Boolean,
    private val shadowsCurrent: () -> Boolean,
) {
    val isCurrent: () -> Boolean = { shadowsCurrent() && isDatabaseCurrent() }

    val visibleDocuments: Set<URI> get() = snapshot.documents.keys - hidden

    fun getDocumentSnapshot(uri: URI): SemanticDocumentSnapshot? = if (uri in hidden) null else snapshot.documents[uri]

    fun findSymbol(symbolId: String): SymbolInfo? =
        snapshot.findDefinitions(symbolId).firstOrNull { it.first !in hidden }?.second

    /** Occurrences carry the symbol ID alone. Two visible files declaring it cannot be distinguished. */
    fun findUniqueSymbol(symbolId: String): SymbolInfo? = uniqueDefinition(symbolId)?.second

    fun uniqueDefinition(symbolId: String): Pair<URI, SymbolInfo>? {
        val definitions = snapshot.findDefinitions(symbolId)
        val first = definitions.firstOrNull { it.first !in hidden } ?: return null
        if (definitions.any { (uri, _) -> uri != first.first && uri !in hidden }) {
            throw ResponseErrorException(
                ResponseError(ResponseErrorCode.RequestFailed, "Multiple visible definitions for $symbolId", null),
            )
        }
        return first
    }

    fun findReferenceDocuments(symbolId: String): Map<URI, SemanticDocumentSnapshot> =
        snapshot.filesReferencing(symbolId).filter { it !in hidden }.associateWith(snapshot.documents::getValue)

    fun findUnambiguousReferenceDocuments(symbolId: String): Map<URI, SemanticDocumentSnapshot> {
        // A constructor reference can be remapped to its class after the original occurrence was checked.
        findUniqueSymbol(symbolId)
        return findReferenceDocuments(symbolId)
    }
}
