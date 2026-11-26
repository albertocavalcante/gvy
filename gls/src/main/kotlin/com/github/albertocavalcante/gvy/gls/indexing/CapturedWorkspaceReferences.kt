// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.indexing

import com.github.albertocavalcante.gvy.semantics.db.ReferenceIndexSnapshot
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentSnapshot
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import java.net.URI

/** A request-scoped reference lookup against one database graph and one set of hidden listed spellings. */
internal class CapturedWorkspaceReferences(
    private val snapshot: ReferenceIndexSnapshot,
    private val hidden: Set<URI>,
    val isCurrent: () -> Boolean,
) {
    val visibleDocuments: Set<URI> get() = snapshot.documents.keys - hidden

    fun getDocumentSnapshot(uri: URI): SemanticDocumentSnapshot? =
        if (uri in hidden) null else snapshot.documents[uri]

    fun findSymbol(symbolId: String): SymbolInfo? =
        snapshot.findDefinitions(symbolId).firstOrNull { it.first !in hidden }?.second

    fun findReferenceDocuments(symbolId: String): Map<URI, SemanticDocumentSnapshot> =
        snapshot.filesReferencing(symbolId).filter { it !in hidden }.associateWith(snapshot.documents::getValue)
}
