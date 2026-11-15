// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import org.eclipse.lsp4j.Diagnostic
import java.net.URI

/** Called under the diagnostics publication lock; each source owns its own contributions. */
internal class ConfigurationDiagnosticOwners {
    private val contributions = mutableMapOf<URI, Map<URI, List<Diagnostic>>>()

    fun replace(owner: URI, notices: Map<URI, List<Diagnostic>>): Set<URI> {
        val previous = contributions.remove(owner).orEmpty().keys
        if (notices.isNotEmpty()) contributions[owner] = notices
        return previous + notices.keys
    }

    fun remove(owner: URI): Set<URI> = contributions.remove(owner).orEmpty().keys

    fun clearTarget(target: URI): Boolean {
        var changed = false
        contributions.entries.toList().forEach { (owner, notices) ->
            if (target in notices) {
                changed = true
                val remaining = notices - target
                if (remaining.isEmpty()) contributions.remove(owner) else contributions[owner] = remaining
            }
        }
        return changed
    }

    fun clear(): Set<URI> = contributions.values.flatMap { it.keys }.toSet().also { contributions.clear() }

    fun forUri(uri: URI): List<Diagnostic> = contributions.values.flatMap { it[uri].orEmpty() }
        .distinctBy { diagnostic -> Triple(diagnostic.range, diagnostic.code, diagnostic.message) }
}
