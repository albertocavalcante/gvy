// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.eclipse.lsp4j.Position
import java.util.Collections

/** Detached syntax evidence. Candidate tiers still require selected-origin validation before navigation. */
internal class SourceTypeReference(
    val range: SourceSpan,
    candidates: List<List<String>>,
    val localDefinition: SourceDefinition? = null,
) {
    val candidates: List<List<String>> = Collections.unmodifiableList(
        candidates.map { tier -> Collections.unmodifiableList(tier.toList()) },
    )
}

internal fun interface SourceTypeIndex {
    fun at(position: Position): SourceTypeReference?
}
