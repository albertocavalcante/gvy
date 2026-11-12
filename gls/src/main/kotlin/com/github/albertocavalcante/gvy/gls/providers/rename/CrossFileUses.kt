// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import java.net.URI

/**
 * Other workspace files that may use a rename target. Any non-empty list, or [unverifiable], makes a rename
 * unsafe: rename edits only the current file, so the caller refuses rather than half-renames.
 *
 * - [used]: the semantic index proves a reference.
 * - [mentioned]: the file's text contains the name (or an accessor spelling of it); the use is not proven.
 * - [unsaved]: an open buffer mentions the name and differs from the text the index was built from.
 * - [truncated]: the scan stopped at the first few files; there may be more.
 * - [unverifiable]: the workspace exceeds the scan limits, so no verdict about other files is possible. A
 *   workspace over the limits therefore refuses every member and class rename.
 * - [unreadable]: directories or files that could not be read, whose contents are unknown.
 */
internal data class CrossFileUses(
    val used: List<URI> = emptyList(),
    val mentioned: List<URI> = emptyList(),
    val unsaved: List<URI> = emptyList(),
    val truncated: Boolean = false,
    val unverifiable: Boolean = false,
    val unreadable: List<URI> = emptyList(),
) {
    val isEmpty: Boolean get() =
        used.isEmpty() && mentioned.isEmpty() && unsaved.isEmpty() && !unverifiable && unreadable.isEmpty()

    /** E.g. `'foo' may be used in 3 other files (A.groovy, B.groovy, C.groovy); renaming across files ...`. */
    fun message(name: String): String {
        if (unverifiable) {
            return "the workspace is too large to verify that no other file uses '$name' (scan limit reached); " +
                "renaming across files is not supported yet"
        }
        if (unreadable.isNotEmpty()) {
            return "'$name' may be used in ${unreadable.named()}, which cannot be read; " +
                "renaming across files is not supported yet"
        }
        val parts = buildList {
            if (used.isNotEmpty()) add("is used in ${used.count()} (${used.named()})")
            if (mentioned.isNotEmpty()) add("may be used in ${mentioned.count(truncated)} (${mentioned.named()})")
            if (unsaved.isNotEmpty()) {
                val verb = if (unsaved.size == 1) "has" else "have"
                add("may be used in ${unsaved.named()}, which $verb unsaved changes not analyzed yet")
            }
        }
        return "'$name' ${parts.joinToString(" and ")}; renaming across files is not supported yet"
    }

    private fun List<URI>.count(atLeast: Boolean = false): String =
        "${if (atLeast) "at least " else ""}$size other file${if (size == 1) "" else "s"}"

    private fun List<URI>.named(): String =
        take(MAX_NAMED).joinToString(", ") { it.fileName() } + if (size > MAX_NAMED) ", …" else ""

    private fun URI.fileName(): String = path?.substringAfterLast('/')?.ifEmpty { null } ?: toString()

    private companion object {
        const val MAX_NAMED = 3
    }
}
