// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import java.net.URI

/** One published semantic graph. File order matches the live reference and definition indexes. */
class ReferenceIndexSnapshot internal constructor(
    private val declarations: DeclarationIndexSnapshot,
    val documents: Map<URI, SemanticDocumentSnapshot>,
    private val referenceFiles: Map<String, List<URI>>,
) {
    fun findDefinitions(symbolId: String): List<Pair<URI, SymbolInfo>> = declarations.findDefinitions(symbolId)

    fun filesReferencing(symbolId: String): List<URI> = referenceFiles[symbolId].orEmpty()
}
